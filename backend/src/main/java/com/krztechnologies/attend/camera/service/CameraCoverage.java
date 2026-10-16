package com.krztechnologies.attend.camera.service;

import com.krztechnologies.attend.camera.repository.EntryPointQueries;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * R5 for other modules (employee tracking): a location "has a camera" when at least one enabled camera belongs to one
 * of its entry points. Without one, employees there cannot be tracked ("&lt;Location&gt; has no camera").
 */
@Service
public class CameraCoverage {

    private final EntryPointQueries entryPoints;

    public CameraCoverage(EntryPointQueries entryPoints) {
        this.entryPoints = entryPoints;
    }

    @Transactional(readOnly = true)
    public boolean locationHasCamera(long locationId) {
        return entryPoints.locationHasEnabledCamera(locationId);
    }
}
