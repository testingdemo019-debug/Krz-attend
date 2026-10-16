package com.krztechnologies.attend.common.security;

import com.krztechnologies.attend.common.error.ErrorWriter;
import com.krztechnologies.attend.common.web.ClientIpResolver;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rate limits (security §8) with Bucket4j buckets in Redis ({@code krz:rl:*}). Over the limit: HTTP 429 with
 * {@code Retry-After}. If Redis is unreachable the request is allowed and a warning logged (degrade, never block the
 * app). Placed in the security filter chain before the OAuth2 redirect filter, so sign-in starts are limited too.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitFilter.class);

    /** The limits of security §8. */
    enum Limit {
        LOGIN_START(10, Duration.ofMinutes(1)),
        ACCESS_REQUEST(3, Duration.ofDays(1)),
        EXPORT(10, Duration.ofMinutes(1)),
        ENROLMENT_CAPTURE(60, Duration.ofMinutes(1)),
        SEARCH(60, Duration.ofMinutes(1)),
        MEDIA_AUTHORIZE(120, Duration.ofMinutes(1)),
        GENERAL_API(300, Duration.ofMinutes(1));

        private final BucketConfiguration configuration;

        Limit(long capacity, Duration period) {
            this.configuration = BucketConfiguration.builder()
                    .addLimit(limit -> limit.capacity(capacity).refillGreedy(capacity, period))
                    .build();
        }
    }

    private final RedisURI redis;
    private final ErrorWriter errors;
    private volatile RedisClient client;
    private volatile StatefulRedisConnection<String, byte[]> connection;
    private volatile ProxyManager<String> buckets;

    public RateLimitFilter(RedisURI redis, ErrorWriter errors) {
        this.redis = redis;
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith("/api/") || path.startsWith("/oauth2/authorization/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Limit limit = limitFor(request);
        String key = "krz:rl:" + limit.name().toLowerCase(Locale.ROOT) + ":" + subject(request, limit);
        ConsumptionProbe probe = consume(key, limit);
        if (probe != null && !probe.isConsumed()) {
            long seconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
            response.setHeader("Retry-After", Long.toString(seconds));
            errors.write(response, 429, "RATE_LIMITED", "Too many requests. Try again in " + seconds + " s.");
            return;
        }
        chain.doFilter(request, response);
    }

    static Limit limitFor(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (path.startsWith("/oauth2/authorization/")) {
            return Limit.LOGIN_START;
        }
        if (path.equals("/api/v1/me/access-request") && "POST".equals(method)) {
            return Limit.ACCESS_REQUEST;
        }
        if (path.endsWith("/export")) {
            return Limit.EXPORT;
        }
        if ((path.matches("^/api/v1/enrolment/sessions/[^/]+/captures$") || path.equals("/api/v1/enrolment/test"))
                && "POST".equals(method)) {
            return Limit.ENROLMENT_CAPTURE;
        }
        if (path.equals("/api/v1/search")) {
            return Limit.SEARCH;
        }
        if (path.equals("/api/v1/media/authorize")) {
            return Limit.MEDIA_AUTHORIZE;
        }
        return Limit.GENERAL_API;
    }

    /** Per user when signed in (sign-in starts always per IP), otherwise per IP. */
    private static String subject(HttpServletRequest request, Limit limit) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (limit != Limit.LOGIN_START
                && authentication != null
                && authentication.getPrincipal() instanceof AttendPrincipal p) {
            return "u:" + p.employeeId();
        }
        return "ip:" + ClientIpResolver.of(request);
    }

    private ConsumptionProbe consume(String key, Limit limit) {
        try {
            return buckets().builder().build(key, () -> limit.configuration).tryConsumeAndReturnRemaining(1);
        } catch (RuntimeException e) {
            LOG.warn("Rate limiting unavailable (Redis): {}", e.getMessage());
            return null;
        }
    }

    private ProxyManager<String> buckets() {
        ProxyManager<String> current = buckets;
        if (current == null) {
            synchronized (this) {
                if (buckets == null) {
                    client = RedisClient.create(redis);
                    connection = client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
                    buckets = Bucket4jLettuce.casBasedBuilder(connection)
                            .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                                    Duration.ofMinutes(10)))
                            .requestTimeout(Duration.ofSeconds(2))
                            .build();
                }
                current = buckets;
            }
        }
        return current;
    }

    @PreDestroy
    void close() {
        if (connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
        }
    }
}
