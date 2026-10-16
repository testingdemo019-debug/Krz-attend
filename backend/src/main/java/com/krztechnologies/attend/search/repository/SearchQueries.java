package com.krztechnologies.attend.search.repository;

import com.krztechnologies.attend.common.sql.LikePattern;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only SQL of the global search (read-only use of the employee, crossing, review and organisation tables, D-038).
 * Every search is a literal "contains" ({@link LikePattern}) with a small limit.
 */
@Repository
public class SearchQueries {

    private final JdbcClient jdbc;

    public SearchQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @param email the work e-mail (UPN) */
    public record EmployeeHit(
            UUID id, String name, String employeeId, String email, String designation, boolean enabled) {}

    /** Employees by name or Employee ID, and by work e-mail only when {@code byEmail}. */
    public List<EmployeeHit> employees(String q, boolean byEmail, int limit) {
        return jdbc.sql("""
                        SELECT e.id, e.display_name, m.hrms_employee_id, e.upn::text AS email, m.designation,
                               e.account_enabled
                          FROM attend.employee e
                          LEFT JOIN attend.employment m ON m.employee_id = e.id
                         WHERE lower(e.display_name) LIKE :q OR lower(coalesce(m.hrms_employee_id, '')) LIKE :q
                            OR (:byEmail AND lower(e.upn::text) LIKE :q)
                         ORDER BY lower(e.display_name)
                         LIMIT :limit
                        """)
                .param("q", LikePattern.contains(q))
                .param("byEmail", byEmail)
                .param("limit", limit)
                .query((rs, n) -> new EmployeeHit(
                        rs.getObject("id", UUID.class),
                        rs.getString("display_name"),
                        rs.getString("hrms_employee_id"),
                        rs.getString("email"),
                        rs.getString("designation"),
                        rs.getBoolean("account_enabled")))
                .list();
    }

    /** @param person the identified employee's name, else the unknown label */
    public record EventHit(String code, Instant occurredAt, String person, String direction) {}

    /** Crossings by event code, newest first. */
    public List<EventHit> events(String q, int limit) {
        return jdbc.sql("""
                        SELECT c.code, c.occurred_at, coalesce(e.display_name, c.unknown_label) AS person, c.direction
                          FROM attend.crossing c
                          LEFT JOIN attend.employee e ON e.id = c.employee_id
                         WHERE lower(c.code) LIKE :q
                         ORDER BY c.occurred_at DESC
                         LIMIT :limit
                        """)
                .param("q", LikePattern.contains(q))
                .param("limit", limit)
                .query((rs, n) -> new EventHit(
                        rs.getString("code"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        rs.getString("person"),
                        rs.getString("direction")))
                .list();
    }

    public record ReviewHit(String code, String type, String label, String status) {}

    /** Review items by code or label, newest first. */
    public List<ReviewHit> reviewItems(String q, int limit) {
        return jdbc.sql("""
                        SELECT code, type, label, status FROM attend.review_item
                         WHERE lower(code) LIKE :q OR lower(coalesce(label, '')) LIKE :q
                         ORDER BY id DESC
                         LIMIT :limit
                        """)
                .param("q", LikePattern.contains(q))
                .param("limit", limit)
                .query((rs, n) -> new ReviewHit(
                        rs.getString("code"), rs.getString("type"), rs.getString("label"), rs.getString("status")))
                .list();
    }

    public record OrgHit(String code, String name) {}

    /** Departments ({@code shifts} false) or shifts by name or code. */
    public List<OrgHit> organisation(String q, boolean shifts, int limit) {
        String table = shifts ? "attend.shift" : "attend.department";
        return jdbc.sql(
                        "SELECT code, name::text AS name FROM " + table
                                + " WHERE lower(name::text) LIKE :q OR lower(code) LIKE :q ORDER BY lower(name::text) LIMIT :limit")
                .param("q", LikePattern.contains(q))
                .param("limit", limit)
                .query((rs, n) -> new OrgHit(rs.getString("code"), rs.getString("name")))
                .list();
    }
}
