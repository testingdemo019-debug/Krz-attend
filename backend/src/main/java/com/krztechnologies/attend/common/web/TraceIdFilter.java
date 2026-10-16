package com.krztechnologies.attend.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Creates or propagates the trace ID (W3C {@code traceparent}), puts it in the MDC as {@code traceId} for every log
 * line, and echoes it in {@code X-Trace-Id}. The same ID goes to the worker in Redis messages and API headers.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "traceId";
    public static final String RESPONSE_HEADER = "X-Trace-Id";
    private static final Pattern TRACEPARENT = Pattern.compile("^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The current request's trace ID, or a fresh one outside a request (jobs). */
    public static String currentTraceId() {
        String id = MDC.get(MDC_KEY);
        return id != null ? id : newTraceId();
    }

    public static String newTraceId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = fromTraceparent(request.getHeader("traceparent"));
        MDC.put(MDC_KEY, traceId);
        response.setHeader(RESPONSE_HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static String fromTraceparent(String header) {
        if (header != null) {
            var matcher = TRACEPARENT.matcher(header.trim());
            if (matcher.matches() && !matcher.group(1).equals("0".repeat(32))) {
                return matcher.group(1);
            }
        }
        return newTraceId();
    }
}
