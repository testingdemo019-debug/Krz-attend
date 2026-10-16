package com.krztechnologies.attend.notification.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The editable values of a notification rule that other modules evaluate themselves: the threshold (NR-01 minutes
 * offline, NR-10 minutes without a worker heartbeat) and the parameters (NR-05 office hours).
 */
@Service
public class RuleSettings {

    private final JdbcClient jdbc;

    public RuleSettings(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The rule's threshold, or {@code fallback} when it has none. */
    @Transactional(readOnly = true)
    public int threshold(String ruleCode, int fallback) {
        return jdbc.sql(
                        "SELECT coalesce((SELECT threshold FROM attend.notification_rule WHERE code = :code), :fallback)")
                .param("code", ruleCode)
                .param("fallback", fallback)
                .query(Integer.class)
                .single();
    }

    /** A text parameter of the rule ({@code params ->> key}), or {@code fallback}. */
    @Transactional(readOnly = true)
    public String param(String ruleCode, String key, String fallback) {
        return jdbc.sql("""
                        SELECT coalesce((SELECT params ->> :key FROM attend.notification_rule WHERE code = :code), :fallback)
                        """)
                .param("key", key)
                .param("code", ruleCode)
                .param("fallback", fallback)
                .query(String.class)
                .single();
    }
}
