package com.krztechnologies.attend.common.settings;

import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.error.ErrorResponse;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.security.CurrentUser;
import com.krztechnologies.attend.common.transaction.AfterCommit;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Typed, validated settings: defaults from application.yml, overridden field by field by the app_setting row. Saving
 * validates, writes with optimistic locking, audits the diff and publishes {@link SettingsChangedEvent}. Values are
 * cached in memory (one backend instance, DECISIONS) and refreshed after a save commits.
 */
@Service
public class SettingsService {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Validator validator;
    private final SettingsDefaults defaults;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final ApplicationEventPublisher events;
    private final Map<String, Versioned<?>> cache = new ConcurrentHashMap<>();

    public SettingsService(
            JdbcClient jdbc,
            JsonMapper json,
            Validator validator,
            SettingsDefaults defaults,
            AuditService audit,
            CurrentUser currentUser,
            ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.json = json;
        this.validator = validator;
        this.defaults = defaults;
        this.audit = audit;
        this.currentUser = currentUser;
        this.events = events;
    }

    /**
     * A settings value with the version to send back when saving it.
     *
     * @param <T> the settings record
     */
    public record Versioned<T>(T value, int version) {}

    public GeneralSettings general() {
        return get(SettingsArea.GENERAL).value();
    }

    public SignInSettings signIn() {
        return get(SettingsArea.SIGN_IN).value();
    }

    public AttendanceRulesSettings attendanceRules() {
        return get(SettingsArea.ATTENDANCE_RULES).value();
    }

    public EnrolmentSettings enrolment() {
        return get(SettingsArea.ENROLMENT).value();
    }

    public PrivacySettings privacy() {
        return get(SettingsArea.PRIVACY).value();
    }

    public HrmsSettings hrms() {
        return get(SettingsArea.HRMS).value();
    }

    @SuppressWarnings("unchecked")
    public <T> Versioned<T> get(SettingsArea<T> area) {
        return (Versioned<T>) cache.computeIfAbsent(area.key(), key -> load(area));
    }

    /** Saves a whole settings area. 409 STALE_VERSION if someone saved it in the meantime. */
    @Transactional
    public <T> Versioned<T> update(SettingsArea<T> area, T value, int expectedVersion) {
        validate(value);
        Versioned<T> current = load(area);
        if (current.version() != expectedVersion) {
            throw new Errors.Conflict(
                    "STALE_VERSION", "Someone else changed these settings. Reload to see the latest.");
        }
        String diff = diff(json.valueToTree(current.value()), json.valueToTree(value));
        int updated = jdbc.sql("""
                        INSERT INTO attend.app_setting (key, value, version, updated_by)
                        VALUES (:key, CAST(:value AS jsonb), 1, :by)
                        ON CONFLICT (key) DO UPDATE
                           SET value = EXCLUDED.value, version = attend.app_setting.version + 1,
                               updated_by = EXCLUDED.updated_by
                         WHERE attend.app_setting.version = :expected
                        """)
                .param("key", area.key())
                .param("value", json.writeValueAsString(value))
                .param("by", currentUser.employeeId())
                .param("expected", expectedVersion)
                .update();
        if (updated == 0) {
            throw new Errors.Conflict(
                    "STALE_VERSION", "Someone else changed these settings. Reload to see the latest.");
        }
        if (!diff.isEmpty()) {
            audit.record(AuditCategory.SETTINGS, "Updated settings", area.label(), null, diff, null);
        }
        AfterCommit.run(() -> {
            cache.remove(area.key());
            events.publishEvent(new SettingsChangedEvent(area.key()));
        });
        return new Versioned<>(value, expectedVersion + 1);
    }

    private <T> Versioned<T> load(SettingsArea<T> area) {
        ObjectNode merged = json.valueToTree(defaultOf(area));
        var row = jdbc.sql("SELECT value::text AS value, version FROM attend.app_setting WHERE key = :key")
                .param("key", area.key())
                .query((rs, n) -> new Stored(rs.getString("value"), rs.getInt("version")))
                .optional();
        int version = 0;
        if (row.isPresent()) {
            JsonNode stored = json.readTree(row.get().value());
            // Only known fields are taken over: an obsolete field in an old row never breaks loading.
            for (String field : merged.propertyNames()) {
                if (stored.has(field) && !stored.get(field).isNull()) {
                    merged.set(field, stored.get(field));
                }
            }
            version = row.get().version();
        }
        T value = json.treeToValue(merged, area.type());
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Stored settings '" + area.key() + "' are invalid: " + violations);
        }
        return new Versioned<>(value, version);
    }

    private Object defaultOf(SettingsArea<?> area) {
        return switch (area.key()) {
            case "general" -> defaults.general();
            case "signin" -> defaults.signin();
            case "attendanceRules" -> defaults.attendanceRules();
            case "enrolment" -> defaults.enrolment();
            case "privacy" -> defaults.privacy();
            case "hrms" -> defaults.hrms();
            default -> throw new IllegalArgumentException("No defaults for settings area " + area.key());
        };
    }

    private <T> void validate(T value) {
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            List<ErrorResponse.FieldError> fields = violations.stream()
                    .map(v -> new ErrorResponse.FieldError(v.getPropertyPath().toString(), v.getMessage()))
                    .toList();
            throw new Errors.BusinessRule("VALIDATION", "Some settings are not valid.", fields);
        }
    }

    /** "field: old → new" for every changed field. */
    private static String diff(JsonNode before, JsonNode after) {
        List<String> changes = new ArrayList<>();
        for (String field : after.propertyNames()) {
            JsonNode old = before.get(field);
            JsonNode now = after.get(field);
            if (old == null || !old.equals(now)) {
                changes.add(field + ": " + (old == null ? "—" : old.toString()) + " → " + now);
            }
        }
        return String.join("; ", changes);
    }

    private record Stored(String value, int version) {}
}
