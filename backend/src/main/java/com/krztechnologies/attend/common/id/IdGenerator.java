package com.krztechnologies.attend.common.id;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Next public code for an entity, from its database sequence through attend.next_code(prefix, seq, width). */
@Component
public class IdGenerator {

    private final JdbcClient jdbc;

    public IdGenerator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public String next(CodeType type) {
        return next(type, "");
    }

    /** A code with a qualifier after the prefix, e.g. {@code next(HOLIDAY, "2026")} → HOL-2026-07. */
    public String next(CodeType type, String qualifier) {
        String prefix = qualifier.isEmpty() ? type.prefix() : type.prefix() + qualifier + "-";
        return jdbc.sql("SELECT attend.next_code(:prefix, CAST(:seq AS regclass), :width)")
                .param("prefix", prefix)
                .param("seq", type.sequence())
                .param("width", type.width())
                .query(String.class)
                .single();
    }
}
