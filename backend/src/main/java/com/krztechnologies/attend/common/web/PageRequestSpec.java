package com.krztechnologies.attend.common.web;

import com.krztechnologies.attend.common.error.Errors;
import java.util.Locale;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Turns the list query parameters {@code page}, {@code size} (≤ 200, default 50) and {@code sort=field,asc|desc} into a
 * {@link Pageable}. Only allow-listed sort fields are accepted; each maps an API field name to an entity property.
 */
public final class PageRequestSpec {

    public static final int MAX_SIZE = 200;
    public static final int DEFAULT_SIZE = 50;

    private PageRequestSpec() {}

    /**
     * @param sortable API sort field → entity property path
     * @param defaultSort used when no sort is given
     */
    public static Pageable toPageable(
            Integer page, Integer size, String sort, Map<String, String> sortable, Sort defaultSort) {
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? DEFAULT_SIZE : size;
        if (pageNumber < 0) {
            throw new Errors.BadRequest("INVALID_PAGE", "page must be 0 or more.");
        }
        if (pageSize < 1 || pageSize > MAX_SIZE) {
            throw new Errors.BadRequest("INVALID_PAGE_SIZE", "size must be between 1 and " + MAX_SIZE + ".");
        }
        return PageRequest.of(pageNumber, pageSize, parseSort(sort, sortable, defaultSort));
    }

    private static Sort parseSort(String sort, Map<String, String> sortable, Sort defaultSort) {
        if (sort == null || sort.isBlank()) {
            return defaultSort;
        }
        String[] parts = sort.split(",", -1);
        String property = sortable.get(parts[0].trim());
        if (property == null || parts.length > 2) {
            throw new Errors.BadRequest("INVALID_SORT", "Sorting by '" + parts[0].trim() + "' is not supported.");
        }
        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length == 2) {
            direction = switch (parts[1].trim().toLowerCase(Locale.ROOT)) {
                case "asc" -> Sort.Direction.ASC;
                case "desc" -> Sort.Direction.DESC;
                default -> throw new Errors.BadRequest("INVALID_SORT", "Sort direction must be asc or desc.");
            };
        }
        return Sort.by(direction, property);
    }
}
