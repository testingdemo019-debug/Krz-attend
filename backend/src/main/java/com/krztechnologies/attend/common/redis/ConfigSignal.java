package com.krztechnologies.attend.common.redis;

import java.util.UUID;

/**
 * A configuration signal for the worker on {@code krz:config} (Redis contract §3): reload one part of the
 * configuration from the database views. The worker also polls {@code v_worker_config_version} every 30 s, so a
 * missed signal only delays a reload.
 *
 * @param camera the camera code ({@code camera} signals)
 * @param employeeId the employee ({@code gallery}, {@code reid.bind} and {@code reid.unbind} signals)
 * @param crossingUid the crossing ({@code reid.bind} and {@code reid.unbind} signals)
 * @param version the new version of what changed: a camera's {@code config_version}; for {@code recognition} the id
 *     of the threshold profile that is now active; for {@code gallery} the gallery version; for {@code reid.*} the
 *     review item's version
 */
public record ConfigSignal(Type type, String camera, UUID employeeId, UUID crossingUid, long version) {

    public static ConfigSignal camera(String code, long configVersion) {
        return new ConfigSignal(Type.CAMERA, code, null, null, configVersion);
    }

    /** One employee's templates or face status changed; {@code galleryVersion} is {@code worker_sync_state}'s. */
    public static ConfigSignal gallery(UUID employeeId, long galleryVersion) {
        return new ConfigSignal(Type.GALLERY, null, employeeId, null, galleryVersion);
    }

    /** A review gave a back-view crossing an identity: its body embeddings follow to the employee's day gallery. */
    public static ConfigSignal reidBind(UUID employeeId, UUID crossingUid, long version) {
        return new ConfigSignal(Type.REID_BIND, null, employeeId, crossingUid, version);
    }

    /** The undo of that decision. */
    public static ConfigSignal reidUnbind(UUID employeeId, UUID crossingUid, long version) {
        return new ConfigSignal(Type.REID_UNBIND, null, employeeId, crossingUid, version);
    }

    public static ConfigSignal recognition(long activeProfileId) {
        return new ConfigSignal(Type.RECOGNITION, null, null, null, activeProfileId);
    }

    /** The signal types of the contract, with their wire names. */
    public enum Type {
        GALLERY("gallery"),
        CAMERA("camera"),
        RECOGNITION("recognition"),
        REID_BIND("reid.bind"),
        REID_UNBIND("reid.unbind");

        private final String wireName;

        Type(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }
}
