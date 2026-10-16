package com.krztechnologies.attend.camera.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only lookups of entry points and their locations (owned by the organisation module) with plain SQL, so the
 * camera module needs no dependency on organisation; and the R5 "location has a camera" check.
 */
@Repository
public class EntryPointQueries {

    private static final String SELECT = """
            SELECT ep.id, ep.code, ep.name, ep.status, l.code AS location_code, l.name::text AS location_name
              FROM attend.entry_point ep
              JOIN attend.location l ON l.id = ep.location_id
            """;

    private final JdbcClient jdbc;

    public EntryPointQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** An entry point with its location. */
    public record EntryPointRow(
            long id, String code, String name, String status, String locationCode, String locationName) {

        public boolean isActive() {
            return "ACTIVE".equals(status);
        }
    }

    public Optional<EntryPointRow> findByCode(String code) {
        return jdbc.sql(SELECT + " WHERE ep.code = :code")
                .param("code", code)
                .query((rs, n) -> row(rs))
                .optional();
    }

    public Map<Long, EntryPointRow> findByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql(SELECT + " WHERE ep.id IN (:ids)").param("ids", ids).query((rs, n) -> row(rs)).list().stream()
                .collect(Collectors.toMap(EntryPointRow::id, Function.identity()));
    }

    /** R5: at least one enabled camera belongs to one of the location's entry points. */
    public boolean locationHasEnabledCamera(long locationId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1
                                         FROM attend.camera c
                                         JOIN attend.entry_point ep ON ep.id = c.entry_point_id
                                        WHERE ep.location_id = :location AND c.enabled)
                        """).param("location", locationId).query(Boolean.class).single();
    }

    private static EntryPointRow row(ResultSet rs) throws SQLException {
        return new EntryPointRow(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("status"),
                rs.getString("location_code"),
                rs.getString("location_name"));
    }
}
