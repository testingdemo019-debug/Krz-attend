package com.krztechnologies.attend.organisation.service;

import com.krztechnologies.attend.common.web.PageRequestSpec;
import com.krztechnologies.attend.common.web.PageResponse;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * One page of an organisation list. Organisation master data is small (departments, shifts, the allocations of one
 * organisation), and its list columns are computed (member counts, today's shift), so lists are built in memory and
 * then sorted by an allow-listed column and cut into the standard {@code {items, page, size, total}} page.
 */
final class Pages {

    private Pages() {}

    /**
     * @param sortable API sort field → comparator (ascending)
     * @param defaultSort one of the sortable fields, used when the request has no {@code sort}
     */
    static <T> PageResponse<T> of(
            List<T> rows,
            Integer page,
            Integer size,
            String sort,
            Map<String, Comparator<T>> sortable,
            Sort defaultSort) {
        Map<String, String> fields =
                sortable.keySet().stream().collect(Collectors.toMap(Function.identity(), Function.identity()));
        Pageable pageable = PageRequestSpec.toPageable(page, size, sort, fields, defaultSort);
        Sort.Order order = pageable.getSort().iterator().next();
        Comparator<T> comparator = sortable.get(order.getProperty());
        List<T> sorted = rows.stream()
                .sorted(order.isAscending() ? comparator : comparator.reversed())
                .toList();
        int from = (int) Math.min(pageable.getOffset(), sorted.size());
        int to = Math.min(from + pageable.getPageSize(), sorted.size());
        return new PageResponse<>(
                sorted.subList(from, to), pageable.getPageNumber(), pageable.getPageSize(), sorted.size());
    }

    /**
     * How many rows have each value, in the order of {@code values} (values no row has are listed with 0, as the
     * prototype lists every option).
     */
    static <T> List<OrganisationViews.Option> count(
            List<T> rows, Function<T, String> value, Map<String, String> values) {
        Map<String, Long> counts = rows.stream().collect(Collectors.groupingBy(value, Collectors.counting()));
        return values.entrySet().stream()
                .map(e -> new OrganisationViews.Option(
                        e.getKey(),
                        e.getValue(),
                        counts.getOrDefault(e.getKey(), 0L).intValue()))
                .toList();
    }

    /** Status values in the prototype's words. */
    static Map<String, String> statuses() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("ACTIVE", "Active");
        values.put("ARCHIVED", "Inactive");
        return values;
    }

    /** Case-insensitive text order with nulls last. */
    static <T> Comparator<T> text(Function<T, String> value) {
        return Comparator.comparing(value, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
    }
}
