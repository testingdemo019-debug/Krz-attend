package com.krztechnologies.attend.common.security;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The 19 permission keys — the single source of truth in code. The database table {@code permission} (seed
 * R__010) holds the same keys with labels; the frontend's list is generated from the OpenAPI spec. Code checks these
 * keys, never role names.
 */
public enum Permission {
    LIVE_VIEW("live.view"),
    EVENTS_VIEW("events.view"),
    EVENTS_EXPORT("events.export"),
    REVIEW_RESOLVE("review.resolve"),
    PEOPLE_VIEW("people.view"),
    PEOPLE_VIEW_SENSITIVE("people.view.sensitive"),
    PEOPLE_ENROL("people.enrol"),
    PEOPLE_DEACTIVATE("people.deactivate"),
    PEOPLE_ERASE("people.erase"),
    ATTENDANCE_VIEW_ALL("attendance.view.all"),
    ATTENDANCE_EDIT("attendance.edit"),
    REPORTS_VIEW("reports.view"),
    SETTINGS_CAMERAS("settings.cameras"),
    SETTINGS_RECOGNITION("settings.recognition"),
    SETTINGS_ATTENDANCE("settings.attendance"),
    SETTINGS_INTEGRATIONS("settings.integrations"),
    SETTINGS_ROLES("settings.roles"),
    SETTINGS_PRIVACY("settings.privacy"),
    AUDIT_VIEW("audit.view");

    private static final Map<String, Permission> BY_KEY =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(Permission::key, Function.identity()));

    private final String key;

    Permission(String key) {
        this.key = key;
    }

    /** The stored and transmitted key, e.g. {@code live.view}. */
    @JsonValue
    public String key() {
        return key;
    }

    public static Optional<Permission> fromKey(String key) {
        return Optional.ofNullable(BY_KEY.get(key));
    }

    @JsonCreator
    static Permission ofKey(String key) {
        return fromKey(key).orElseThrow(() -> new IllegalArgumentException("Unknown permission " + key));
    }
}
