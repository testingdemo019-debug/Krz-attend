package com.krztechnologies.attend.common.audit;

import com.krztechnologies.attend.common.security.CurrentUser;
import com.krztechnologies.attend.common.security.PermissionLookup;
import com.krztechnologies.attend.common.web.ClientIpResolver;
import com.krztechnologies.attend.common.web.TraceIdFilter;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the append-only, hash-chained audit log in the CALLER's transaction, so a business change and its audit
 * entry commit or roll back together. The database trigger assigns the AU code and the hashes. Values are summaries:
 * never secrets, embeddings or image bytes.
 */
@Service
public class AuditService {

    private static final int MAX_VALUE_LENGTH = 4000;

    private final JdbcClient jdbc;
    private final CurrentUser currentUser;
    private final PermissionLookup permissionLookup;
    private final JsonMapper json;

    public AuditService(JdbcClient jdbc, CurrentUser currentUser, PermissionLookup permissionLookup, JsonMapper json) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.permissionLookup = permissionLookup;
        this.json = json;
    }

    /** When an action was last recorded (Settings → Microsoft sign-in: the last test), if ever. */
    @Transactional(readOnly = true)
    public Optional<Instant> lastRecorded(AuditCategory category, String action) {
        return jdbc.sql("""
                        SELECT occurred_at FROM attend.audit_log
                         WHERE category = :category AND action = :action
                         ORDER BY occurred_at DESC LIMIT 1
                        """)
                .param("category", category.name())
                .param("action", action)
                .query((rs, n) -> rs.getObject(1, OffsetDateTime.class).toInstant())
                .optional();
    }

    /** Records an action by the signed-in user. Returns the AU code. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String record(
            AuditCategory category, String action, String target, String before, String after, Map<String, ?> details) {
        var principal = currentUser.require();
        return record(
                AuditActor.employee(principal.employeeId(), principal.displayName()),
                category,
                action,
                target,
                before,
                after,
                details);
    }

    /** Records an action by an explicit actor (a system actor, or a user during sign-in). Returns the AU code. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String record(
            AuditActor actor,
            AuditCategory category,
            String action,
            String target,
            String before,
            String after,
            Map<String, ?> details) {
        String roles =
                actor.employeeId() == null ? null : String.join(", ", permissionLookup.roleNamesOf(actor.employeeId()));
        return jdbc.sql("""
                        INSERT INTO attend.audit_log (actor_id, actor_label, actor_roles, actor_ip, category, action, target,
                                                      before_value, after_value, details, trace_id)
                        VALUES (:actorId, :actorLabel, :actorRoles, CAST(:actorIp AS inet), :category, :action, :target,
                                :before, :after, CAST(:details AS jsonb), :traceId)
                        RETURNING code
                        """)
                .param("actorId", actor.employeeId())
                .param("actorLabel", actor.label())
                .param("actorRoles", roles == null || roles.isEmpty() ? null : roles)
                .param("actorIp", ClientIpResolver.current().orElse(null))
                .param("category", category.name())
                .param("action", action)
                .param("target", target)
                .param("before", truncate(before))
                .param("after", truncate(after))
                .param("details", details == null || details.isEmpty() ? null : json.writeValueAsString(details))
                .param("traceId", TraceIdFilter.currentTraceId())
                .query(String.class)
                .single();
    }

    private static String truncate(String value) {
        return value == null || value.length() <= MAX_VALUE_LENGTH
                ? value
                : value.substring(0, MAX_VALUE_LENGTH - 1) + "…";
    }
}
