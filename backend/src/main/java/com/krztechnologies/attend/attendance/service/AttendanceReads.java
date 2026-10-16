package com.krztechnologies.attend.attendance.service;

import com.krztechnologies.attend.attendance.repository.AttendanceQueries;
import com.krztechnologies.attend.rules.service.ShiftTerms;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Mapping shared by the attendance read models. */
final class AttendanceReads {

    private AttendanceReads() {}

    static Map<UUID, AttendanceViews.PersonRef> persons(Collection<AttendanceQueries.Person> people) {
        Map<UUID, AttendanceViews.PersonRef> refs = new LinkedHashMap<>();
        for (AttendanceQueries.Person p : people) {
            refs.put(
                    p.id(),
                    new AttendanceViews.PersonRef(
                            p.id(),
                            p.name(),
                            p.employeeId(),
                            p.departmentCode() == null
                                    ? null
                                    : new AttendanceViews.Ref(p.departmentCode(), p.departmentName()),
                            p.locationCode() == null
                                    ? null
                                    : new AttendanceViews.Ref(p.locationCode(), p.locationName()),
                            p.employmentType()));
        }
        return refs;
    }

    static AttendanceViews.ShiftRef shift(ShiftTerms terms) {
        return new AttendanceViews.ShiftRef(terms.shiftCode(), terms.shiftName(), terms.colour(), terms.timingText());
    }

    /** A list filter: null or empty lets everything through, else the value must be one of them. */
    static boolean any(List<String> values, String value) {
        return values == null || values.isEmpty() || values.contains(value);
    }

    /** How many items have each value (one value per item; null values left out), by label. */
    static <T> List<AttendanceViews.Option> options(List<T> items, Function<T, AttendanceViews.Ref> value) {
        return optionsOfAll(items, item -> {
            AttendanceViews.Ref ref = value.apply(item);
            return ref == null ? List.of() : List.of(ref);
        });
    }

    /** How many items have each value at least once (several values per item), by label. */
    static <T> List<AttendanceViews.Option> optionsOfAll(List<T> items, Function<T, List<AttendanceViews.Ref>> values) {
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (T item : items) {
            for (AttendanceViews.Ref ref : new LinkedHashSet<>(values.apply(item))) {
                labels.putIfAbsent(ref.code(), ref.name());
                counts.merge(ref.code(), 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .map(e -> new AttendanceViews.Option(e.getKey(), labels.get(e.getKey()), e.getValue()))
                .sorted(Comparator.comparing(AttendanceViews.Option::label, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }
}
