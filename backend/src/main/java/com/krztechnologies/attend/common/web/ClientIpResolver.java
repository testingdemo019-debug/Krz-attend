package com.krztechnologies.attend.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The client IP of the current request. Tomcat's RemoteIpValve (server.forward-headers-strategy=native) has already
 * applied X-Forwarded-For — but only when the request came from a trusted proxy (nginx on the internal network, or
 * localhost for the Vite dev proxy; {@code server.tomcat.remoteip.internal-proxies}). A forged header from anywhere else
 * is ignored.
 */
public final class ClientIpResolver {

    private ClientIpResolver() {}

    public static String of(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    /** The IP of the request bound to this thread, if any (empty in jobs). */
    public static Optional<String> current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return Optional.of(of(attributes.getRequest()));
        }
        return Optional.empty();
    }
}
