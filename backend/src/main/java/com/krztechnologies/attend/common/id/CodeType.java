package com.krztechnologies.attend.common.id;

/**
 * Public identifiers generated from database sequences (vocabulary "Public identifiers"). Audit codes are assigned by
 * the audit_log chain trigger, not here.
 */
public enum CodeType {
    ACCESS_REQUEST("AR-", "attend.seq_access_request_code", 4),
    SYNC_RUN("SY-", "attend.seq_sync_run_code", 1),
    NOTIFICATION("NT-", "attend.seq_notification_code", 6),
    EMAIL("EM-", "attend.seq_email_code", 6),
    CROSSING("EV-", "attend.seq_crossing_code", 6),
    REVIEW("RV-", "attend.seq_review_code", 4),
    OUTBOX("OB-", "attend.seq_outbox_code", 6),
    ALLOCATION("ALC-", "attend.seq_allocation_code", 4),
    /** HOL-&lt;year&gt;-nn: generated with {@link IdGenerator#next(CodeType, String)} and the year as qualifier. */
    HOLIDAY("HOL-", "attend.seq_holiday_code", 2),
    PRIVACY_REQUEST("PR-", "attend.seq_privacy_request_code", 3),
    THRESHOLD_PROFILE("TP-", "attend.seq_threshold_profile_code", 1),
    ENTRY_POINT("EP-", "attend.seq_entry_point_code", 2),
    CAMERA("CAM-", "attend.seq_camera_code", 2),
    ENROLMENT_SESSION("ES-", "attend.seq_enrolment_session_code", 6);

    private final String prefix;
    private final String sequence;
    private final int width;

    CodeType(String prefix, String sequence, int width) {
        this.prefix = prefix;
        this.sequence = sequence;
        this.width = width;
    }

    public String prefix() {
        return prefix;
    }

    public String sequence() {
        return sequence;
    }

    public int width() {
        return width;
    }
}
