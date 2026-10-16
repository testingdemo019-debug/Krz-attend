package com.krztechnologies.attend.directory.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The tenant's accounts outside the sync-scope groups ({@code directory_unsynced}), kept by the directory sync. */
@Repository
public class UnsyncedAccountStore {

    private final JdbcClient jdbc;

    public UnsyncedAccountStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** An account outside the sync scope. */
    public record Account(UUID objectId, String displayName, String upn) {}

    /** The sync saw this enabled member outside every sync-scope group. */
    public void outsideScope(UUID objectId, String displayName, String upn, Instant seenAt) {
        jdbc.sql("""
                        INSERT INTO attend.directory_unsynced (entra_object_id, display_name, upn, seen_at)
                        VALUES (:id, :name, :upn, :seen)
                        ON CONFLICT (entra_object_id)
                        DO UPDATE SET display_name = EXCLUDED.display_name, upn = EXCLUDED.upn, seen_at = EXCLUDED.seen_at
                        """)
                .param("id", objectId)
                .param("name", displayName)
                .param("upn", upn)
                .param("seen", Timestamp.from(seenAt))
                .update();
    }

    /** The account is in scope, excluded for another reason, disabled or deleted. */
    public void forget(UUID objectId) {
        jdbc.sql("DELETE FROM attend.directory_unsynced WHERE entra_object_id = :id")
                .param("id", objectId)
                .update();
    }

    /** After a full sync: everyone it did not see outside the scope again. */
    public void forgetNotSeenSince(Instant since) {
        jdbc.sql("DELETE FROM attend.directory_unsynced WHERE seen_at < :since")
                .param("since", Timestamp.from(since))
                .update();
    }

    public List<Account> all() {
        return jdbc.sql("SELECT entra_object_id, display_name, upn::text AS upn FROM attend.directory_unsynced"
                        + " ORDER BY lower(display_name), upn")
                .query((rs, n) -> new Account(
                        rs.getObject("entra_object_id", UUID.class), rs.getString("display_name"), rs.getString("upn")))
                .list();
    }
}
