package com.krztechnologies.attend.camera.service;

import com.krztechnologies.attend.camera.domain.CalibrationRun;
import com.krztechnologies.attend.camera.domain.Camera;
import com.krztechnologies.attend.camera.repository.CalibrationRunRepository;
import com.krztechnologies.attend.camera.repository.CameraRepository;
import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.crypto.CryptoService;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.security.CurrentUser;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.common.worker.WorkerClient;
import com.krztechnologies.attend.common.worker.WorkerException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The camera actions that go through the recognition worker: test connection, snapshot, ONVIF discovery and the
 * calibration test walk. No database transaction is held while the worker works (a test may take 20 s); the camera
 * password is decrypted in memory only for the test.
 */
@Service
public class CameraConnectionService {

    public static final int DEFAULT_WALK_SECONDS = 60;

    private final CameraRepository cameras;
    private final CalibrationRunRepository runs;
    private final WorkerClient worker;
    private final CryptoService crypto;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final TimeService time;
    private final TransactionTemplate transactions;
    private final JsonMapper json;

    public CameraConnectionService(
            CameraRepository cameras,
            CalibrationRunRepository runs,
            WorkerClient worker,
            CryptoService crypto,
            AuditService audit,
            CurrentUser currentUser,
            TimeService time,
            TransactionTemplate transactions,
            JsonMapper json) {
        this.cameras = cameras;
        this.runs = runs;
        this.worker = worker;
        this.crypto = crypto;
        this.audit = audit;
        this.currentUser = currentUser;
        this.time = time;
        this.transactions = transactions;
        this.json = json;
    }

    /** The stored result of a finished test walk (calibration_run.result). */
    record StoredResult(String verdict, List<WorkerClient.CalibrationCrossing> crossings) {}

    /** "Test connection" with the saved settings: RTSP DESCRIBE and the first frame of the main stream. */
    public CameraViews.TestResult test(String code) {
        Camera camera = find(code);
        if (camera.getUsername() != null && !camera.hasPassword()) {
            return new CameraViews.TestResult(
                    false, null, null, null, null, "PASSWORD_NOT_SET", "Set the password first.", null);
        }
        String password = camera.hasPassword()
                ? new String(crypto.decrypt(camera.getPasswordEnc()), StandardCharsets.UTF_8)
                : null;
        WorkerClient.CameraTestResult result = worker.testCamera(new WorkerClient.CameraConnection(
                camera.getHost(), camera.getRtspPort(), camera.getMainPath(), camera.getUsername(), password));
        String errorCode = result.ok() ? null : result.errorCode() == null ? "WORKER_ERROR" : result.errorCode();
        transactions.executeWithoutResult(status -> audit.record(
                AuditCategory.CAMERAS,
                "Tested camera connection",
                camera.getName(),
                null,
                result.ok() ? "Connected" : "Failed: " + errorCode,
                null));
        return new CameraViews.TestResult(
                result.ok(),
                result.codec(),
                result.resolution(),
                result.fps(),
                result.latencyMs(),
                errorCode,
                errorCode == null ? null : WorkerException.describe(errorCode),
                camera.getClockOffsetSeconds());
    }

    /** The latest main-stream frame (tripwire editor background, Live View fallback). */
    public byte[] snapshot(String code) {
        return worker.snapshot(find(code).getCode());
    }

    /**
     * "Find cameras on the network": the worker's unicast ONVIF probe; addresses already configured are marked.
     *
     * @param subnet an IPv4 /24 (or smaller) CIDR, or null for CAMERA_DISCOVERY_SUBNET
     */
    public List<CameraViews.DiscoveredCamera> discover(String subnet) {
        List<WorkerClient.DiscoveredCamera> found = worker.discover(subnet);
        Map<String, String> inUse = new HashMap<>();
        transactions
                .execute(status -> cameras.findAllByOrderByCodeAsc())
                .forEach(c -> inUse.putIfAbsent(c.getHost(), c.getName()));
        return found.stream()
                .map(f -> new CameraViews.DiscoveredCamera(
                        f.ip(), f.manufacturer(), f.model(), f.onvifUrl(), inUse.get(f.ip())))
                .toList();
    }

    /** Starts a test walk: for its duration the worker reports this camera's crossings here, not to the stream. */
    public CameraViews.CalibrationView startCalibration(String code, int durationSeconds) {
        Camera camera = find(code);
        if (!camera.isEnabled()) {
            throw new Errors.BusinessRule("CAMERA_DISABLED", "Turn processing on before running a test walk.");
        }
        String workerRunId = worker.startCalibration(camera.getCode(), durationSeconds);
        CalibrationRun run = transactions.execute(status ->
                runs.save(new CalibrationRun(camera.getId(), workerRunId, currentUser.employeeId(), time.now())));
        return new CameraViews.CalibrationView(
                run.getId(), run.getStatus().name(), run.getStartedAt(), null, null, List.of());
    }

    /** Polls a test walk; when the worker reports the end, the result is stored and audited once. */
    public CameraViews.CalibrationView calibration(String code, long runId) {
        Camera camera = find(code);
        CalibrationRun run = transactions.execute(status -> runs.findByIdAndCameraId(runId, camera.getId())
                .orElseThrow(() -> new Errors.NotFound("Test walk not found.")));
        if (run.getStatus() != CalibrationRun.Status.RUNNING) {
            StoredResult stored = run.getResult() == null
                    ? new StoredResult(null, List.of())
                    : json.readValue(run.getResult(), StoredResult.class);
            return view(run, stored);
        }
        WorkerClient.CalibrationStatus progress = worker.calibration(camera.getCode(), run.getWorkerRunId());
        CalibrationRun.Status outcome = switch (String.valueOf(progress.status())) {
            case "running" -> CalibrationRun.Status.RUNNING;
            case "completed" -> CalibrationRun.Status.COMPLETED;
            case "failed" -> CalibrationRun.Status.FAILED;
            default -> throw new WorkerException("WORKER_ERROR");
        };
        if (outcome == CalibrationRun.Status.RUNNING) {
            return view(run, new StoredResult(null, progress.crossings()));
        }
        StoredResult result = new StoredResult(
                outcome == CalibrationRun.Status.COMPLETED
                        ? verdict(progress.crossings(), camera.getFacesVisibleWhen())
                        : null,
                progress.crossings());
        CalibrationRun finished = transactions.execute(status -> {
            CalibrationRun current = runs.findById(run.getId()).orElseThrow();
            if (current.getStatus() != CalibrationRun.Status.RUNNING) {
                return current;
            }
            current.finish(outcome, time.now(), json.writeValueAsString(result));
            audit.record(
                    AuditCategory.CAMERAS,
                    "Ran calibration test walk",
                    camera.getName(),
                    null,
                    verdictLabel(outcome, result.verdict()),
                    null);
            return runs.save(current);
        });
        return view(finished, result);
    }

    /**
     * The test walk faces the camera first, then walks away: with faces visible when entering that must read IN, then
     * OUT. A first crossing in the other direction means the inside direction is reversed. U-turns do not count.
     */
    static String verdict(List<WorkerClient.CalibrationCrossing> crossings, FacesVisibleWhen faces) {
        List<String> directions = crossings.stream()
                .filter(c -> !c.uTurn())
                .map(WorkerClient.CalibrationCrossing::direction)
                .toList();
        if (directions.isEmpty()) {
            return "NO_CROSSINGS";
        }
        String towardsCamera = faces == FacesVisibleWhen.ENTERING ? "IN" : "OUT";
        if (!towardsCamera.equals(directions.getFirst())) {
            return "REVERSED";
        }
        return directions.stream().skip(1).anyMatch(d -> !d.equals(towardsCamera)) ? "PASSED" : "INCONCLUSIVE";
    }

    private static String verdictLabel(CalibrationRun.Status outcome, String verdict) {
        if (outcome == CalibrationRun.Status.FAILED) {
            return "Failed";
        }
        return switch (verdict) {
            case "PASSED" -> "Passed";
            case "REVERSED" -> "Directions reversed";
            case "NO_CROSSINGS" -> "No crossings seen";
            default -> "Inconclusive";
        };
    }

    private static CameraViews.CalibrationView view(CalibrationRun run, StoredResult result) {
        return new CameraViews.CalibrationView(
                run.getId(),
                run.getStatus().name(),
                run.getStartedAt(),
                run.getFinishedAt(),
                result.verdict(),
                result.crossings());
    }

    private Camera find(String code) {
        return transactions.execute(
                status -> cameras.findByCode(code).orElseThrow(() -> new Errors.NotFound("Camera not found.")));
    }
}
