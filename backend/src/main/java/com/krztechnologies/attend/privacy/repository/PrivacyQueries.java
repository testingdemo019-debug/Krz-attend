package com.krztechnologies.attend.privacy.repository;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only lookups the privacy pages need from other modules' tables (names, consent counts), as native SQL so the
 * employee module can later call privacy services (erasure) without a dependency cycle.
 */
@Repository
public class PrivacyQueries {

    private final JdbcClient jdbc;

    public PrivacyQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Display names of the given employees (unknown IDs are left out). */
    public Map<UUID, String> names(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jdbc
                .sql("SELECT id, display_name FROM attend.employee WHERE id IN (:ids)")
                .param("ids", ids)
                .query((rs, n) -> Map.entry(rs.getObject("id", UUID.class), rs.getString("display_name")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** People whose current consent was given against an older notice than the active one. */
    public long consentedToOlderNotice(int activeVersion) {
        return jdbc.sql("""
                        SELECT count(*) FROM attend.consent
                         WHERE is_current AND status = 'GIVEN' AND notice_version < :active
                        """).param("active", activeVersion).query(Long.class).single();
    }

    /** Serialises notice publishing (version numbers and the single active notice) until the transaction ends. */
    public void lockNotices() {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('attend.consent_notice'))")
                .query((rs, n) -> 1)
                .single();
    }
}
