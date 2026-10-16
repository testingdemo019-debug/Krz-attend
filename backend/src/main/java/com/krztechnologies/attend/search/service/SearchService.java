package com.krztechnologies.attend.search.service;

import com.krztechnologies.attend.common.security.CurrentUser;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.search.repository.SearchQueries;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The topbar's global search (the prototype's {@code searchResults}), each group only for those who may see it:
 * employees (people.view or people.enrol; work e-mail only with people.view.sensitive, otherwise the designation),
 * events by code (events.view), review items (review.resolve), departments and shifts (settings.attendance). Pages are
 * matched by the frontend in its own navigation model, which already knows their permissions.
 */
@Service
public class SearchService {

    private static final int EMPLOYEES = 7;
    private static final int EVENTS = 5;
    private static final int REVIEW_ITEMS = 4;
    private static final int ORGANISATION = 3;

    private final SearchQueries queries;
    private final CurrentUser currentUser;

    public SearchService(SearchQueries queries, CurrentUser currentUser) {
        this.queries = queries;
        this.currentUser = currentUser;
    }

    /**
     * @param sub the work e-mail with people.view.sensitive, else the designation
     * @param employeeId HRMS Employee ID, null when missing ("No Employee ID")
     */
    public record EmployeeResult(
            UUID id,
            String name,
            @Nullable String employeeId,
            @Nullable String sub,
            boolean enabled) {}

    /** @param person the employee, else the unknown-face label, else null */
    public record EventResult(
            String code, Instant occurredAt, @Nullable String person, String direction) {}

    /** @param label the unknown face's label, or null (then the type) */
    public record ReviewResult(
            String code, String type, @Nullable String label, String status) {}

    public record OrgResult(String code, String name) {}

    /** Groups the user may not see are empty. */
    public record Results(
            List<EmployeeResult> employees,
            List<EventResult> events,
            List<ReviewResult> reviewItems,
            List<OrgResult> departments,
            List<OrgResult> shifts) {}

    @Transactional(readOnly = true)
    public Results search(String q) {
        String text = q == null ? "" : q.trim();
        if (text.isEmpty()) {
            return new Results(List.of(), List.of(), List.of(), List.of(), List.of());
        }
        boolean sensitive = currentUser.has(Permission.PEOPLE_VIEW_SENSITIVE);
        boolean people = currentUser.has(Permission.PEOPLE_VIEW) || currentUser.has(Permission.PEOPLE_ENROL);
        boolean organisation = currentUser.has(Permission.SETTINGS_ATTENDANCE);
        return new Results(
                people
                        ? queries.employees(text, sensitive, EMPLOYEES).stream()
                                .map(e -> new EmployeeResult(
                                        e.id(),
                                        e.name(),
                                        e.employeeId(),
                                        sensitive ? e.email() : e.designation(),
                                        e.enabled()))
                                .toList()
                        : List.of(),
                currentUser.has(Permission.EVENTS_VIEW)
                        ? queries.events(text, EVENTS).stream()
                                .map(e -> new EventResult(e.code(), e.occurredAt(), e.person(), e.direction()))
                                .toList()
                        : List.of(),
                currentUser.has(Permission.REVIEW_RESOLVE)
                        ? queries.reviewItems(text, REVIEW_ITEMS).stream()
                                .map(r -> new ReviewResult(r.code(), r.type(), r.label(), r.status()))
                                .toList()
                        : List.of(),
                organisation ? org(queries.organisation(text, false, ORGANISATION)) : List.of(),
                organisation ? org(queries.organisation(text, true, ORGANISATION)) : List.of());
    }

    private static List<OrgResult> org(List<SearchQueries.OrgHit> hits) {
        return hits.stream().map(h -> new OrgResult(h.code(), h.name())).toList();
    }
}
