package com.krztechnologies.attend.common.undo;

import com.krztechnologies.attend.common.settings.SettingsService;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The undo tokens of the toasts (corrections, review decisions): opaque, single use, valid for Settings → General →
 * undo window, and only for the person who made the change. Kept in Redis ({@code krz:undo:<token>}, short-lived
 * data); what an undo restores lives in the database.
 */
@Component
public class UndoTokens {

    private static final String PREFIX = "krz:undo:";
    private static final String KIND = "kind";
    private static final String OWNER = "owner";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final SettingsService settings;
    private final JsonMapper json;

    public UndoTokens(StringRedisTemplate redis, SettingsService settings, JsonMapper json) {
        this.redis = redis;
        this.settings = settings;
        this.json = json;
    }

    /** A new token for {@code data}; the caller returns it with {@link #windowSeconds()}. */
    public String issue(String kind, UUID owner, Map<String, String> data) {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Map<String, String> value = new LinkedHashMap<>(data);
        value.put(KIND, kind);
        value.put(OWNER, owner.toString());
        redis.opsForValue().set(PREFIX + token, json.writeValueAsString(value), Duration.ofSeconds(windowSeconds()));
        return token;
    }

    /** The undo window in seconds. */
    public int windowSeconds() {
        return settings.general().undoWindowSeconds();
    }

    /**
     * Uses the token up: its data when it is valid for this kind and owner; empty when it expired, was used, or
     * belongs to someone else or another action.
     */
    public Optional<Map<String, String>> redeem(String kind, UUID owner, String token) {
        if (token == null || token.isBlank() || token.length() > 64) {
            return Optional.empty();
        }
        String stored = redis.opsForValue().getAndDelete(PREFIX + token);
        if (stored == null) {
            return Optional.empty();
        }
        Map<String, String> value = json.readValue(stored, new TypeReference<Map<String, String>>() {});
        if (!kind.equals(value.get(KIND)) || !owner.toString().equals(value.get(OWNER))) {
            return Optional.empty();
        }
        return Optional.of(value);
    }
}
