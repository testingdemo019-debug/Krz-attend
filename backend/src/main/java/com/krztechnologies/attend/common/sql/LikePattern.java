package com.krztechnologies.attend.common.sql;

import java.util.Locale;

/** Free-text search in SQL: what someone typed, as a {@code LIKE} pattern that finds it literally anywhere. */
public final class LikePattern {

    private LikePattern() {}

    /**
     * {@code %text%}, trimmed and lower-cased (for {@code lower(column) LIKE :q} or {@code ILIKE}), with the backslash,
     * {@code %} and {@code _} escaped by PostgreSQL's default escape character, the backslash.
     */
    public static String contains(String text) {
        return "%"
                + text.trim()
                        .toLowerCase(Locale.ROOT)
                        .replace("\\", "\\\\")
                        .replace("%", "\\%")
                        .replace("_", "\\_")
                + "%";
    }
}
