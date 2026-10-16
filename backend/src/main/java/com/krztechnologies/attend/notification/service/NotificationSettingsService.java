package com.krztechnologies.attend.notification.service;

import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.error.ErrorResponse;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.feature.FeatureFlags;
import com.krztechnologies.attend.common.security.CurrentUser;
import com.krztechnologies.attend.notification.domain.NotificationRule;
import com.krztechnologies.attend.notification.repository.NotificationRuleRepository;
import com.krztechnologies.attend.notification.repository.NotificationSettingsQueries;
import com.krztechnologies.attend.notification.repository.NotificationSettingsQueries.PersonRow;
import com.krztechnologies.attend.notification.repository.NotificationSettingsQueries.RoleRow;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Settings → Notifications: every rule (NR-01 … NR-11) with its channels, threshold, parameters, quiet hours, digest
 * and recipients (roles and individual people with access). Push is shown but stays off ("Mobile app not available
 * yet", PENDING P-06). Every change is validated, optimistic-locked and audited.
 */
@Service
public class NotificationSettingsService {

    static final int MIN_THRESHOLD = 1;
    static final int MAX_THRESHOLD = 240;
    static final String PUSH_NOTE = "Mobile app not available yet";
    private static final Pattern TIME = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    private final NotificationRuleRepository rules;
    private final NotificationSettingsQueries queries;
    private final FeatureFlags features;
    private final CurrentUser currentUser;
    private final AuditService audit;
    private final JsonMapper json;

    public NotificationSettingsService(
            NotificationRuleRepository rules,
            NotificationSettingsQueries queries,
            FeatureFlags features,
            CurrentUser currentUser,
            AuditService audit,
            JsonMapper json) {
        this.rules = rules;
        this.queries = queries;
        this.features = features;
        this.currentUser = currentUser;
        this.audit = audit;
        this.json = json;
    }

    /** A role as a recipient choice: {@code id} is its public code. */
    public record RoleOption(String id, String name) {}

    /**
     * A person as a recipient.
     *
     * @param hasAccess false when they lost access (they are skipped when notifications go out)
     */
    public record PersonOption(UUID id, String name, boolean hasAccess) {}

    /**
     * One rule as the page shows it.
     *
     * @param thresholdUnit {@code MINUTES} or {@code DAYS}; null when the rule has no threshold
     * @param params rule parameters (NR-05: office hours {@code officeStart}/{@code officeEnd}, IST)
     * @param push always false while push is not available
     */
    public record RuleView(
            String code,
            String label,
            String description,
            String severity,
            boolean enabled,
            @Nullable Integer threshold,
            @Nullable String thresholdUnit,
            @Nullable JsonNode params,
            boolean inApp,
            boolean email,
            boolean push,
            @Nullable LocalTime quietStart,
            @Nullable LocalTime quietEnd,
            String digest,
            @Nullable LocalTime digestTime,
            List<RoleOption> roles,
            List<PersonOption> users,
            int version) {}

    /**
     * The page.
     *
     * @param availableRoles every role, for the recipient picker
     * @param availableUsers everyone with access, for the recipient picker
     * @param mailEnabled false when e-mail is turned off (MAIL_ENABLED=false)
     */
    public record NotificationSettingsView(
            List<RuleView> rules,
            List<RoleOption> availableRoles,
            List<PersonOption> availableUsers,
            boolean mailEnabled,
            boolean pushAvailable,
            String pushNote) {}

    /**
     * An edit of one rule.
     *
     * @param params null keeps the parameters; otherwise exactly the rule's keys, each an IST time {@code HH:mm}
     * @param roleIds role codes (ROLE-…)
     */
    public record RuleChange(
            boolean enabled,
            Integer threshold,
            boolean inApp,
            boolean email,
            boolean push,
            LocalTime quietStart,
            LocalTime quietEnd,
            NotificationRule.Digest digest,
            LocalTime digestTime,
            Map<String, String> params,
            List<String> roleIds,
            List<UUID> userIds,
            int version) {}

    @Transactional(readOnly = true)
    public NotificationSettingsView view() {
        List<RoleRow> roles = queries.roles();
        List<NotificationRule> all = rules.findAll().stream()
                .sorted(Comparator.comparing(NotificationRule::getCode))
                .toList();
        Set<UUID> userIds = all.stream().flatMap(r -> r.getUserIds().stream()).collect(Collectors.toSet());
        Map<UUID, PersonRow> people =
                queries.people(userIds).stream().collect(Collectors.toMap(PersonRow::id, Function.identity()));
        return new NotificationSettingsView(
                all.stream().map(rule -> toView(rule, roles, people)).toList(),
                roles.stream().map(r -> new RoleOption(r.code(), r.name())).toList(),
                queries.peopleWithAccess().stream()
                        .map(p -> new PersonOption(p.id(), p.name(), true))
                        .toList(),
                features.mailEnabled(),
                false,
                PUSH_NOTE);
    }

    @Transactional
    public RuleView update(String code, RuleChange change) {
        NotificationRule rule =
                rules.findById(code).orElseThrow(() -> new Errors.NotFound("Notification rule not found."));
        if (rule.getVersion() != change.version()) {
            throw new Errors.Conflict("STALE_VERSION", "Someone else changed this rule. Reload to see the latest.");
        }
        List<RoleRow> roles = queries.roles();
        Map<String, RoleRow> rolesByCode = roles.stream().collect(Collectors.toMap(RoleRow::code, Function.identity()));
        List<ErrorResponse.FieldError> errors = new ArrayList<>();
        validate(rule, change, errors);
        Set<Long> roleIds = new HashSet<>();
        for (String roleCode : change.roleIds()) {
            RoleRow role = rolesByCode.get(roleCode);
            if (role == null) {
                errors.add(new ErrorResponse.FieldError("roleIds", "Role " + roleCode + " does not exist."));
            } else {
                roleIds.add(role.id());
            }
        }
        Set<UUID> userIds = new HashSet<>(change.userIds());
        Set<UUID> beforeAndAfter = new HashSet<>(userIds);
        beforeAndAfter.addAll(rule.getUserIds());
        Map<UUID, PersonRow> people =
                queries.people(beforeAndAfter).stream().collect(Collectors.toMap(PersonRow::id, Function.identity()));
        for (UUID userId : userIds) {
            PersonRow person = people.get(userId);
            // Someone who lost access may stay listed (they are skipped when sending); new recipients need access.
            boolean keptFromBefore = person != null && rule.getUserIds().contains(userId);
            if (person == null || !(person.hasAccess() || keptFromBefore)) {
                errors.add(
                        new ErrorResponse.FieldError("userIds", "Only people with access can receive notifications."));
            }
        }
        if (change.enabled() && (change.inApp() || change.email()) && roleIds.isEmpty() && userIds.isEmpty()) {
            errors.add(new ErrorResponse.FieldError(
                    "recipients", "Every enabled notification needs at least one recipient."));
        }
        if (!errors.isEmpty()) {
            throw new Errors.BusinessRule("VALIDATION", "Some values are not valid.", errors);
        }
        Map<String, String> before = describe(rule, roles, people);
        rule.update(
                new NotificationRule.Edit(
                        change.enabled(),
                        change.threshold(),
                        change.inApp(),
                        change.email(),
                        change.quietStart(),
                        change.quietEnd(),
                        change.digest(),
                        change.digest() == NotificationRule.Digest.DAILY ? change.digestTime() : null),
                change.params() == null ? rule.getParams() : json.writeValueAsString(change.params()),
                roleIds,
                userIds,
                currentUser.employeeId());
        NotificationRule saved = rules.saveAndFlush(rule);
        Map<String, String> after = describe(saved, roles, people);
        List<String> changed = before.keySet().stream()
                .filter(key -> !Objects.equals(before.get(key), after.get(key)))
                .toList();
        if (!changed.isEmpty()) {
            audit.record(
                    AuditCategory.SETTINGS,
                    "Updated notification rule",
                    saved.getCode() + " · " + saved.getLabel(),
                    summary(changed, before),
                    summary(changed, after),
                    null);
        }
        return toView(saved, roles, people);
    }

    private void validate(NotificationRule rule, RuleChange change, List<ErrorResponse.FieldError> errors) {
        if (change.push()) {
            errors.add(new ErrorResponse.FieldError("push", PUSH_NOTE + "."));
        }
        if (rule.getThresholdUnit() == null && change.threshold() != null) {
            errors.add(new ErrorResponse.FieldError("threshold", "This notification has no threshold."));
        }
        if (rule.getThresholdUnit() != null
                && (change.threshold() == null
                        || change.threshold() < MIN_THRESHOLD
                        || change.threshold() > MAX_THRESHOLD)) {
            errors.add(new ErrorResponse.FieldError(
                    "threshold", "Enter a threshold between " + MIN_THRESHOLD + " and " + MAX_THRESHOLD + "."));
        }
        if ((change.quietStart() == null) != (change.quietEnd() == null)) {
            errors.add(new ErrorResponse.FieldError("quietEnd", "Quiet hours need a start and an end."));
        } else if (change.quietStart() != null && change.quietStart().equals(change.quietEnd())) {
            errors.add(
                    new ErrorResponse.FieldError("quietEnd", "Quiet hours must end at another time than they start."));
        }
        if (change.digest() == NotificationRule.Digest.DAILY && change.digestTime() == null) {
            errors.add(new ErrorResponse.FieldError("digestTime", "Choose when the daily digest is sent."));
        }
        if (change.params() != null) {
            Set<String> keys = new HashSet<>();
            json.readTree(rule.getParams()).propertyNames().forEach(keys::add);
            if (!keys.equals(change.params().keySet())) {
                errors.add(new ErrorResponse.FieldError("params", "Parameters must be exactly: " + keys + "."));
            } else if (change.params().values().stream()
                    .anyMatch(v -> v == null || !TIME.matcher(v).matches())) {
                errors.add(new ErrorResponse.FieldError("params", "Enter times as HH:mm (IST)."));
            }
        }
    }

    private RuleView toView(NotificationRule rule, List<RoleRow> roles, Map<UUID, PersonRow> people) {
        return new RuleView(
                rule.getCode(),
                rule.getLabel(),
                rule.getDescription(),
                rule.getSeverity().name(),
                rule.isEnabled(),
                rule.getThreshold(),
                rule.getThresholdUnit(),
                json.readTree(rule.getParams()),
                rule.isInApp(),
                rule.isEmail(),
                false,
                rule.getQuietStart(),
                rule.getQuietEnd(),
                rule.getDigest().name(),
                rule.getDigestTime(),
                roles.stream()
                        .filter(r -> rule.getRoleIds().contains(r.id()))
                        .map(r -> new RoleOption(r.code(), r.name()))
                        .toList(),
                rule.getUserIds().stream()
                        .map(id -> people.getOrDefault(id, new PersonRow(id, "Unknown person", false)))
                        .sorted(Comparator.comparing(PersonRow::name))
                        .map(p -> new PersonOption(p.id(), p.name(), p.hasAccess()))
                        .toList(),
                rule.getVersion());
    }

    /** Field → readable value, for the audit entry's before and after. */
    private Map<String, String> describe(NotificationRule rule, List<RoleRow> roles, Map<UUID, PersonRow> people) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("enabled", rule.isEnabled() ? "on" : "off");
        values.put(
                "threshold", rule.getThreshold() == null ? "—" : rule.getThreshold() + " " + rule.getThresholdUnit());
        values.put("in app", rule.isInApp() ? "on" : "off");
        values.put("e-mail", rule.isEmail() ? "on" : "off");
        values.put(
                "quiet hours", rule.getQuietStart() == null ? "none" : rule.getQuietStart() + "–" + rule.getQuietEnd());
        values.put(
                "delivery",
                rule.getDigest() == NotificationRule.Digest.DAILY
                        ? "daily digest at " + rule.getDigestTime()
                        : "immediately");
        JsonNode params = json.readTree(rule.getParams());
        values.put("parameters", params.isEmpty() ? "—" : params.toString());
        values.put(
                "roles",
                roles.stream()
                        .filter(r -> rule.getRoleIds().contains(r.id()))
                        .map(RoleRow::name)
                        .collect(Collectors.joining(", ")));
        values.put(
                "people",
                rule.getUserIds().stream()
                        .map(id -> people.containsKey(id) ? people.get(id).name() : id.toString())
                        .sorted()
                        .collect(Collectors.joining(", ")));
        return values;
    }

    private static String summary(List<String> fields, Map<String, String> values) {
        return fields.stream()
                .map(field -> field + ": " + (values.get(field).isEmpty() ? "none" : values.get(field)))
                .collect(Collectors.joining("; "));
    }
}
