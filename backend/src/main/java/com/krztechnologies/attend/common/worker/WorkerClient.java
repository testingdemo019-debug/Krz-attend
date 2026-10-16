package com.krztechnologies.attend.common.worker;

import java.io.InterruptedIOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Backend side of the worker's internal API (FastAPI on the internal network). Every route except {@code /health}
 * carries {@code X-Worker-Token}. Timeouts: 3 s to connect; 5 s to answer, 20 s for embeddings, the camera test and
 * the ONVIF discovery (the worker probes for 4 s). Errors become a {@link WorkerException} with the worker's code. Request bodies
 * (camera passwords) and responses (images) are never logged.
 */
@Component
public class WorkerClient {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerClient.class);
    private static final String TOKEN_HEADER = "X-Worker-Token";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration LONG_READ_TIMEOUT = Duration.ofSeconds(20);
    private static final int DISCOVERY_SECONDS = 4;
    private static final int MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024;
    private static final Pattern ERROR_CODE = Pattern.compile("^[A-Z][A-Z_]{1,39}$");

    private final WorkerProperties worker;
    private final JsonMapper json;
    private final RestClient quick;
    private final RestClient slow;

    public WorkerClient(WorkerProperties worker, JsonMapper json) {
        this.worker = worker;
        this.json = json;
        this.quick = restClient(READ_TIMEOUT);
        this.slow = restClient(LONG_READ_TIMEOUT);
    }

    /** What the worker needs to open a camera stream; {@link #toString()} leaves the credentials out. */
    public record CameraConnection(String host, int port, String path, String username, String password) {

        @Override
        public String toString() {
            return "CameraConnection[" + host + ":" + port + "]";
        }
    }

    /**
     * Result of {@code POST /v1/cameras/test}.
     *
     * @param errorCode AUTH_FAILED, TIMEOUT, UNREACHABLE or UNSUPPORTED_CODEC when {@code ok} is false
     */
    public record CameraTestResult(
            boolean ok, String codec, String resolution, Double fps, Integer latencyMs, String errorCode) {}

    /** One answer to the unicast ONVIF probe. */
    public record DiscoveredCamera(String ip, String manufacturer, String model, String onvifUrl) {}

    /** A loaded model as reported by {@code /health}. */
    public record Model(String name, String version, String sha256, String licence) {}

    /**
     * {@code GET /health} (non-sensitive).
     *
     * @param status ok, degraded or starting
     * @param provider the ONNX Runtime execution provider (tensorrt, cuda, cpu)
     */
    public record Health(String status, String version, String provider, List<Model> models) {}

    /** A crossing seen during a calibration test walk (reported to the caller instead of the stream). */
    public record CalibrationCrossing(Instant ts, String direction, Double confidence, Integer frames, boolean uTurn) {}

    /**
     * {@code GET /v1/cameras/{code}/calibration/{run_id}}.
     *
     * @param status running, completed or failed
     */
    public record CalibrationStatus(String status, List<CalibrationCrossing> crossings) {}

    /**
     * The face quality the worker measured.
     *
     * @param score the overall quality 0–100
     */
    public record FaceQuality(
            Integer eyePx, Double yaw, Double pitch, Integer sharp, Integer brightness, Integer score) {}

    /**
     * One image of {@code /v1/embeddings}: how many faces it shows and, for exactly one, its quality and embedding.
     *
     * @param embedding the float32 vector as raw bytes (never logged; the caller encrypts it), or null
     * @param livenessPassed null in upload mode
     * @param model the recognition model, e.g. {@code sface-2021dec}
     */
    public record FaceEmbedding(
            int faces,
            FaceQuality quality,
            Boolean livenessPassed,
            Double livenessScore,
            byte[] embedding,
            Integer dim,
            String model) {}

    /**
     * Detection, quality, liveness (live mode) and the embedding of each image (enrolment captures and uploads,
     * templates from review); 20 s.
     *
     * @param images JPEG or PNG, already re-encoded by the backend, at most 10
     * @param mode {@code live} or {@code upload}
     */
    public List<FaceEmbedding> embeddings(List<byte[]> images, String mode) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        for (int i = 0; i < images.size(); i++) {
            String name = "image-" + (i + 1) + ".jpg";
            parts.add("images", new ByteArrayResource(images.get(i)) {
                @Override
                public String getFilename() {
                    return name;
                }
            });
        }
        parts.add("mode", mode);
        URI uri = uri("/v1/embeddings");
        JsonNode body = call(
                "embeddings",
                () -> read(slow.post()
                        .uri(uri)
                        .header(TOKEN_HEADER, worker.token())
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .accept(MediaType.APPLICATION_JSON)
                        .body(parts)
                        .retrieve()
                        .body(String.class)));
        List<FaceEmbedding> results = new ArrayList<>();
        for (JsonNode image : body) {
            JsonNode quality = image.path("quality");
            JsonNode liveness = image.path("liveness");
            String vector = text(image, "embedding");
            results.add(new FaceEmbedding(
                    image.path("faces").asInt(0),
                    quality.isObject()
                            ? new FaceQuality(
                                    integer(quality, "eye_px"),
                                    number(quality, "yaw"),
                                    number(quality, "pitch"),
                                    integer(quality, "sharp"),
                                    integer(quality, "brightness"),
                                    integer(quality, "score"))
                            : null,
                    liveness.isObject() ? liveness.path("passed").asBoolean(false) : null,
                    liveness.isObject() ? number(liveness, "score") : null,
                    vector == null ? null : decode(vector),
                    integer(image, "dim"),
                    text(image, "model")));
        }
        if (results.size() != images.size()) {
            LOG.warn("Worker embeddings answered {} results for {} images", results.size(), images.size());
            throw new WorkerException("WORKER_ERROR");
        }
        return List.copyOf(results);
    }

    /** An enrolled employee whose gallery entry resembles the new face. */
    public record Lookalike(String employeeId, double score) {}

    /**
     * The enrolment duplicate check: other enrolled employees above the threshold, most similar first (the worker's
     * in-memory gallery).
     *
     * @param embeddings the new face's embeddings (raw float32 bytes; sent base64, never logged)
     */
    public List<Lookalike> duplicates(List<byte[]> embeddings, String excludeEmployeeId, double threshold) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put(
                "embeddings",
                embeddings.stream()
                        .map(e -> Base64.getEncoder().encodeToString(e))
                        .toList());
        request.put("exclude_employee_id", excludeEmployeeId);
        request.put("threshold", threshold);
        JsonNode body = post(quick, "duplicates", uri("/v1/duplicates"), request);
        List<Lookalike> found = new ArrayList<>();
        for (JsonNode match : body) {
            found.add(new Lookalike(
                    text(match, "employee_id"), match.path("score").asDouble()));
        }
        return List.copyOf(found);
    }

    private static byte[] decode(String base64) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new WorkerException("WORKER_ERROR");
        }
    }

    public Health health() {
        URI uri = uri("/health");
        JsonNode body = call(
                "health",
                () -> read(quick.get()
                        .uri(uri)
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(String.class)));
        List<Model> models = new ArrayList<>();
        for (JsonNode model : body.path("models")) {
            models.add(new Model(
                    text(model, "name"), text(model, "version"), text(model, "sha256"), text(model, "licence")));
        }
        return new Health(text(body, "status"), text(body, "version"), text(body, "provider"), List.copyOf(models));
    }

    /** RTSP DESCRIBE plus the first frame, capped at 10 s by the worker. */
    public CameraTestResult testCamera(CameraConnection camera) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("host", camera.host());
        request.put("port", camera.port());
        request.put("path", camera.path());
        request.put("username", camera.username());
        request.put("password", camera.password());
        JsonNode body = post(slow, "camera test", uri("/v1/cameras/test"), request);
        return new CameraTestResult(
                body.path("ok").asBoolean(false),
                text(body, "codec"),
                text(body, "resolution"),
                number(body, "fps"),
                integer(body, "latency_ms"),
                text(body, "error_code"));
    }

    /**
     * Unicast WS-Discovery probe of every host in the subnet (the worker's CAMERA_DISCOVERY_SUBNET when null).
     *
     * @param subnet an IPv4 CIDR of /24 or smaller, or null
     */
    public List<DiscoveredCamera> discover(String subnet) {
        Map<String, Object> request = new LinkedHashMap<>();
        if (subnet != null) {
            request.put("subnet", subnet);
        }
        request.put("timeout_s", DISCOVERY_SECONDS);
        JsonNode body = post(slow, "ONVIF discovery", uri("/v1/discovery/onvif"), request);
        List<DiscoveredCamera> found = new ArrayList<>();
        for (JsonNode camera : body) {
            found.add(new DiscoveredCamera(
                    text(camera, "ip"),
                    text(camera, "manufacturer"),
                    text(camera, "model"),
                    text(camera, "onvif_url")));
        }
        return List.copyOf(found);
    }

    /** The latest main-stream frame as JPEG (downscaled by the worker to 1280 px wide), without overlays. */
    public byte[] snapshot(String cameraCode) {
        URI uri = UriComponentsBuilder.fromUri(worker.url())
                .path("/v1/cameras/{code}/snapshot")
                .queryParam("overlays", "false")
                .encode()
                .buildAndExpand(cameraCode)
                .toUri();
        byte[] jpeg = call(
                "snapshot",
                () -> quick.get()
                        .uri(uri)
                        .header(TOKEN_HEADER, worker.token())
                        .accept(MediaType.IMAGE_JPEG)
                        .retrieve()
                        .body(byte[].class));
        boolean isJpeg = jpeg != null
                && jpeg.length >= 3
                && jpeg[0] == (byte) 0xFF
                && jpeg[1] == (byte) 0xD8
                && jpeg[2] == (byte) 0xFF;
        if (!isJpeg || jpeg.length > MAX_SNAPSHOT_BYTES) {
            LOG.warn("Worker snapshot of {} is not a JPEG of at most {} bytes", cameraCode, MAX_SNAPSHOT_BYTES);
            throw new WorkerException("WORKER_ERROR");
        }
        return jpeg;
    }

    /** Starts a calibration test walk; returns the worker's run ID. */
    public String startCalibration(String cameraCode, int durationSeconds) {
        JsonNode body = post(
                quick,
                "calibration start",
                uri("/v1/cameras/{code}/calibration", cameraCode),
                Map.of("duration_s", durationSeconds));
        String runId = text(body, "run_id");
        if (runId == null || runId.isBlank() || runId.length() > 64) {
            throw new WorkerException("WORKER_ERROR");
        }
        return runId;
    }

    public CalibrationStatus calibration(String cameraCode, String runId) {
        URI uri = uri("/v1/cameras/{code}/calibration/{run}", cameraCode, runId);
        JsonNode body = call(
                "calibration status",
                () -> read(quick.get()
                        .uri(uri)
                        .header(TOKEN_HEADER, worker.token())
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(String.class)));
        List<CalibrationCrossing> crossings = new ArrayList<>();
        for (JsonNode crossing : body.path("crossings")) {
            crossings.add(new CalibrationCrossing(
                    instant(text(crossing, "ts")),
                    text(crossing, "direction"),
                    number(crossing, "confidence"),
                    integer(crossing, "frames"),
                    crossing.path("u_turn").asBoolean(false)));
        }
        return new CalibrationStatus(text(body, "status"), List.copyOf(crossings));
    }

    private JsonNode post(RestClient client, String what, URI uri, Map<String, Object> request) {
        String payload = json.writeValueAsString(request);
        return call(
                what,
                () -> read(client.post()
                        .uri(uri)
                        .header(TOKEN_HEADER, worker.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .body(String.class)));
    }

    private <T> T call(String what, Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            String code = status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()
                    ? "WORKER_TOKEN_REFUSED"
                    : errorCode(e.getResponseBodyAsString());
            LOG.warn("Worker {} failed: HTTP {} {}", what, status, code);
            throw new WorkerException(code);
        } catch (ResourceAccessException e) {
            boolean timeout = e.getCause() instanceof InterruptedIOException;
            LOG.warn("Worker {} failed: {} ({})", what, timeout ? "timeout" : "unreachable", worker.url());
            throw new WorkerException(timeout ? "WORKER_TIMEOUT" : "WORKER_UNREACHABLE");
        } catch (JacksonException | DateTimeParseException e) {
            LOG.warn(
                    "Worker {} returned an unreadable answer: {}",
                    what,
                    e.getClass().getSimpleName());
            throw new WorkerException("WORKER_ERROR");
        }
    }

    private JsonNode read(String body) {
        return json.readTree(body == null || body.isBlank() ? "{}" : body);
    }

    /** The {@code code} of the worker's error envelope {code, message}; WORKER_ERROR when there is none. */
    private String errorCode(String body) {
        try {
            String code = json.readTree(body).path("code").asString("");
            return ERROR_CODE.matcher(code).matches() ? code : "WORKER_ERROR";
        } catch (JacksonException e) {
            return "WORKER_ERROR";
        }
    }

    private URI uri(String path, Object... variables) {
        return UriComponentsBuilder.fromUri(worker.url())
                .path(path)
                .encode()
                .buildAndExpand(variables)
                .toUri();
    }

    private static RestClient restClient(Duration readTimeout) {
        var factory = ClientHttpRequestFactoryBuilder.httpComponents()
                .build(HttpClientSettings.defaults()
                        .withTimeouts(CONNECT_TIMEOUT, readTimeout)
                        .withRedirects(HttpRedirects.DONT_FOLLOW));
        return RestClient.builder().requestFactory(factory).build();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }

    private static Double number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asDouble() : null;
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asInt() : null;
    }

    private static Instant instant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return OffsetDateTime.parse(value).toInstant();
        }
    }
}
