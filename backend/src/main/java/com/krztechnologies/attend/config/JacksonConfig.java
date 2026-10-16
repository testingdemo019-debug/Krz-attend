package com.krztechnologies.attend.config;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.ext.javatime.ser.LocalTimeSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * API conventions Spring Boot's Jackson defaults do not cover: a time of day (shift start, quiet hours, digest time) is
 * {@code HH:mm} — the API never carries seconds of a clock time. Dates and instants keep ISO-8601 (REST conventions).
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    @Bean
    JacksonModule clockTimeModule() {
        return new SimpleModule("krz-clock-time")
                .addSerializer(LocalTime.class, new LocalTimeSerializer(DateTimeFormatter.ofPattern("HH:mm")));
    }
}
