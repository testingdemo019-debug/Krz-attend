package com.krztechnologies.attend.notification.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Who receives a rule's notifications: users of the rule's roles plus its individual users — only people who still
 * have access (a role) and an enabled account. A read-only query over the access tables, so every module can call the
 * notification service without a dependency cycle (DECISIONS).
 */
@Repository
public class RecipientQueries {

    private final JdbcClient jdbc;

    public RecipientQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param address the work e-mail (mail, else the UPN)
     */
    public record Recipient(UUID employeeId, String displayName, String address) {}

    public List<Recipient> recipientsOf(String ruleCode) {
        return jdbc.sql("""
                        SELECT e.id, e.display_name, coalesce(e.mail, e.upn)::text AS address
                          FROM attend.employee e
                         WHERE e.account_enabled
                           AND EXISTS (SELECT 1 FROM attend.user_role ur WHERE ur.employee_id = e.id)
                           AND (EXISTS (SELECT 1 FROM attend.user_role ur
                                          JOIN attend.notification_rule_role nr ON nr.role_id = ur.role_id
                                         WHERE ur.employee_id = e.id AND nr.rule_code = :rule)
                                OR EXISTS (SELECT 1 FROM attend.notification_rule_user nu
                                            WHERE nu.employee_id = e.id AND nu.rule_code = :rule))
                         ORDER BY e.display_name
                        """)
                .param("rule", ruleCode)
                .query((rs, n) -> new Recipient(
                        rs.getObject("id", UUID.class), rs.getString("display_name"), rs.getString("address")))
                .list();
    }

    /** One user's own work address (test e-mail "to me"). */
    public Recipient self(UUID employeeId) {
        return jdbc.sql(
                        "SELECT e.id, e.display_name, coalesce(e.mail, e.upn)::text AS address FROM attend.employee e WHERE e.id = :id")
                .param("id", employeeId)
                .query((rs, n) -> new Recipient(
                        rs.getObject("id", UUID.class), rs.getString("display_name"), rs.getString("address")))
                .single();
    }
}
