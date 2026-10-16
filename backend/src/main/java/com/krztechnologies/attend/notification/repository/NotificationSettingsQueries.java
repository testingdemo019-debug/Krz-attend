package com.krztechnologies.attend.notification.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The recipient choices of Settings → Notifications: roles (by their public code) and people with access. A read-only
 * query over the access tables, like {@link RecipientQueries}, so the access module stays free to notify (DECISIONS).
 */
@Repository
public class NotificationSettingsQueries {

    private final JdbcClient jdbc;

    public NotificationSettingsQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param code the role's public code (ROLE-…)
     */
    public record RoleRow(long id, String code, String name) {}

    /**
     * @param hasAccess whether the person can currently receive notifications (enabled account with a role)
     */
    public record PersonRow(UUID id, String name, boolean hasAccess) {}

    public List<RoleRow> roles() {
        return jdbc.sql("SELECT id, code, name::text AS name FROM attend.role ORDER BY name")
                .query((rs, n) -> new RoleRow(rs.getLong("id"), rs.getString("code"), rs.getString("name")))
                .list();
    }

    /** Everyone who can receive notifications: an enabled account with at least one role. */
    public List<PersonRow> peopleWithAccess() {
        return jdbc.sql("""
                        SELECT e.id, e.display_name
                          FROM attend.employee e
                         WHERE e.account_enabled
                           AND EXISTS (SELECT 1 FROM attend.user_role ur WHERE ur.employee_id = e.id)
                         ORDER BY e.display_name
                        """)
                .query((rs, n) -> new PersonRow(rs.getObject("id", UUID.class), rs.getString("display_name"), true))
                .list();
    }

    /** The named people (unknown IDs are left out), with whether they still have access. */
    public List<PersonRow> people(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT e.id, e.display_name,
                               e.account_enabled AND EXISTS (SELECT 1 FROM attend.user_role ur WHERE ur.employee_id = e.id)
                                 AS has_access
                          FROM attend.employee e
                         WHERE e.id IN (:ids)
                         ORDER BY e.display_name
                        """)
                .param("ids", ids)
                .query((rs, n) -> new PersonRow(
                        rs.getObject("id", UUID.class), rs.getString("display_name"), rs.getBoolean("has_access")))
                .list();
    }
}
