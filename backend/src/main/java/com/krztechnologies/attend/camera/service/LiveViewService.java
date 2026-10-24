package com.krztechnologies.attend.camera.service;

import com.krztechnologies.attend.camera.domain.Camera;
import com.krztechnologies.attend.camera.domain.CameraGeometry;
import com.krztechnologies.attend.camera.repository.CameraRepository;
import com.krztechnologies.attend.camera.repository.EntryPointQueries;
import com.krztechnologies.attend.camera.repository.EntryPointQueries.EntryPointRow;
import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.error.Errors;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Live View: one tile per enabled camera (disabled cameras have no stream to show) with the status and the geometry
 * for the tripwire and zone overlays; and the audit entry when someone saves a snapshot in the browser.
 */
@Service
public class LiveViewService {

    private final CameraRepository cameras;
    private final EntryPointQueries entryPoints;
    private final GeometryJson geometry;
    private final AuditService audit;

    public LiveViewService(
            CameraRepository cameras, EntryPointQueries entryPoints, GeometryJson geometry, AuditService audit) {
        this.cameras = cameras;
        this.entryPoints = entryPoints;
        this.geometry = geometry;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<CameraViews.LiveTile> tiles() {
        List<Camera> enabled = cameras.findAllByEnabledTrueOrderByCodeAsc();
        Map<Long, EntryPointRow> points = entryPoints.findByIds(
                enabled.stream().map(Camera::getEntryPointId).collect(Collectors.toSet()));
        return enabled.stream()
                .map(camera -> {
                    CameraGeometry shape = geometry.of(camera);
                    EntryPointRow entryPoint = points.get(camera.getEntryPointId());
                    return new CameraViews.LiveTile(
                            camera.getCode(),
                            camera.getName(),
                            entryPoint == null ? null : entryPoint.name(),
                            camera.getStatus(),
                            camera.getStatusSince(),
                            camera.getFps(),
                            camera.getLatencyMs(),
                            camera.getResolution(),
                            camera.getDirectionMode(),
                            shape.countingMethod(),
                            shape.zone(),
                            shape.wire(),
                            shape.doorZone());
                })
                .toList();
    }

    /** Whether the browser may open the stream of this camera code: an enabled camera has one (the media check). */
    @Transactional(readOnly = true)
    public boolean streamable(String cameraCode) {
        return cameras.findByCode(cameraCode).map(Camera::isEnabled).orElse(false);
    }

    /** The browser saved a snapshot of a tile: audit only (category Live View). */
    @Transactional
    public void snapshotSaved(String cameraCode) {
        Camera camera = cameras.findByCode(cameraCode).orElseThrow(() -> new Errors.NotFound("Camera not found."));
        audit.record(AuditCategory.LIVE_VIEW, "Saved camera snapshot", camera.getName(), null, null, null);
    }
}
