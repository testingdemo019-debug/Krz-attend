package com.krztechnologies.attend.crossing.service;

import com.krztechnologies.attend.rules.service.CrossingMethod;
import com.krztechnologies.attend.rules.service.Direction;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One message of the stream {@code krz:crossings} (Redis contract §1), parsed and validated. An unknown {@code v}, a
 * missing or malformed field makes the message poison ({@link Invalid}); it is never retried.
 *
 * @param method {@code FACE} or {@code REID}; null for {@code NONE}
 * @param employeeId the identity (AUTO) or the top candidate; null when empty
 * @param quality JSON object of the best face frame, or null
 * @param candidates JSON array of the top candidates, or null
 * @param enrolmentCapture a walk while the person's face was being captured at the entry point (optional field,
 *     absent = false; D-118): recorded, never counted, never a review item
 */
public record CrossingMessage(
        UUID crossingUid,
        String cameraCode,
        Instant ts,
        Direction direction,
        boolean uTurn,
        Hint hint,
        CrossingMethod method,
        UUID employeeId,
        BigDecimal score,
        BigDecimal secondScore,
        BigDecimal margin,
        Integer agreeK,
        Integer agreeN,
        BigDecimal reidScore,
        String liveness,
        BigDecimal livenessScore,
        String quality,
        String candidates,
        String unknownCluster,
        String faceCrop,
        String faceCropBlur,
        String bodyCrop,
        String bodyCropBlur,
        Integer trackId,
        String modelVersion,
        String workerId,
        String traceId,
        boolean enrolmentCapture) {

    /** The schema version this backend reads. */
    public static final String VERSION = "1";

    private static final Pattern CAMERA_CODE = Pattern.compile("^CAM-\\d{2,}$");
    private static final Pattern CLUSTER = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}:[A-Z]\\d+$");
    private static final Pattern CROP_PATH = Pattern.compile("^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$");
    private static final Pattern SHORT_TEXT = Pattern.compile("^[\\x21-\\x7e]{1,64}$");
    private static final Set<String> LIVENESS = Set.of("PASSED", "FAILED", "SKIPPED");
    private static final BigDecimal ONE = BigDecimal.ONE;

    /** The worker's recognition result (R14). */
    public enum Hint {
        AUTO,
        UNCERTAIN,
        UNKNOWN,
        LIVENESS_FAIL,
        REID_UNCERTAIN
    }

    /** A poison message: the contract was not met. */
    public static final class Invalid extends RuntimeException {
        public Invalid(String message) {
            super(message);
        }
    }

    /** Parses the stream fields (all strings; empty = absent). */
    public static CrossingMessage parse(Map<String, String> fields, JsonMapper json) {
        Fields f = new Fields(fields);
        String version = f.required("v");
        if (!VERSION.equals(version)) {
            throw new Invalid("Unknown schema version v=" + version);
        }
        String camera = f.required("camera_code");
        if (!CAMERA_CODE.matcher(camera).matches()) {
            throw new Invalid("camera_code is not a camera code");
        }
        Hint hint = f.enumValue("decision_hint", Hint.class);
        String methodText = f.required("method");
        CrossingMethod method = switch (methodText) {
            case "FACE" -> CrossingMethod.FACE;
            case "REID" -> CrossingMethod.REID;
            case "NONE" -> null;
            default -> throw new Invalid("method must be FACE, REID or NONE");
        };
        UUID employee = f.uuid("employee_id");
        if (hint == Hint.AUTO && (employee == null || method == null)) {
            throw new Invalid("An AUTO crossing needs employee_id and method FACE or REID");
        }
        String liveness = f.optional("liveness");
        if (liveness != null && !LIVENESS.contains(liveness)) {
            throw new Invalid("liveness must be PASSED, FAILED or SKIPPED");
        }
        String cluster = f.optional("unknown_cluster");
        if (cluster != null && !CLUSTER.matcher(cluster).matches()) {
            throw new Invalid("unknown_cluster is malformed");
        }
        String candidates = f.json("candidates", json);
        if (candidates != null && !json.readTree(candidates).isArray()) {
            throw new Invalid("candidates must be a JSON array");
        }
        String quality = f.json("quality", json);
        if (quality != null && !json.readTree(quality).isObject()) {
            throw new Invalid("quality must be a JSON object");
        }
        return new CrossingMessage(
                f.requiredUuid("crossing_uid"),
                camera,
                f.instant("ts"),
                f.enumValue("direction", Direction.class),
                f.bool("u_turn"),
                hint,
                method,
                employee,
                f.score("score"),
                f.score("second_score"),
                f.score("margin"),
                f.count("agree_k", Short.MAX_VALUE),
                f.count("agree_n", Short.MAX_VALUE),
                f.score("reid_score"),
                liveness,
                f.score("liveness_score"),
                quality,
                candidates,
                cluster,
                f.cropPath("face_crop"),
                f.cropPath("face_crop_blur"),
                f.cropPath("body_crop"),
                f.cropPath("body_crop_blur"),
                f.count("track_id", Integer.MAX_VALUE),
                f.text("model_version", 200),
                f.shortText("worker_id"),
                f.shortText("trace_id"),
                f.optional("enrolment_capture") != null && f.bool("enrolment_capture"));
    }

    /** "Unknown #A3" for the cluster {@code 2026-10-12:A3}, or null. */
    public String unknownLabel() {
        return unknownCluster == null ? null : "Unknown #" + unknownCluster.substring(unknownCluster.indexOf(':') + 1);
    }

    private record Fields(Map<String, String> values) {

        String optional(String name) {
            String value = values.get(name);
            return value == null || value.isEmpty() ? null : value;
        }

        String required(String name) {
            String value = optional(name);
            if (value == null) {
                throw new Invalid("Missing field " + name);
            }
            return value;
        }

        UUID requiredUuid(String name) {
            UUID value = uuid(name);
            if (value == null) {
                throw new Invalid("Missing field " + name);
            }
            return value;
        }

        UUID uuid(String name) {
            String value = optional(name);
            try {
                return value == null ? null : UUID.fromString(value);
            } catch (IllegalArgumentException e) {
                throw new Invalid(name + " is not a UUID");
            }
        }

        Instant instant(String name) {
            try {
                return Instant.parse(required(name));
            } catch (DateTimeParseException e) {
                throw new Invalid(name + " is not an ISO-8601 instant");
            }
        }

        <E extends Enum<E>> E enumValue(String name, Class<E> type) {
            try {
                return Enum.valueOf(type, required(name));
            } catch (IllegalArgumentException e) {
                throw new Invalid(name + " has an unknown value");
            }
        }

        boolean bool(String name) {
            return switch (required(name)) {
                case "true" -> true;
                case "false" -> false;
                default -> throw new Invalid(name + " must be true or false");
            };
        }

        /** A similarity or liveness score: −1…1, four decimals at most in the database. */
        BigDecimal score(String name) {
            String value = optional(name);
            if (value == null) {
                return null;
            }
            try {
                BigDecimal score = new BigDecimal(value);
                if (score.abs().compareTo(ONE) > 0) {
                    throw new Invalid(name + " is out of range");
                }
                return score.setScale(4, RoundingMode.HALF_UP);
            } catch (NumberFormatException e) {
                throw new Invalid(name + " is not a number");
            }
        }

        Integer count(String name, int max) {
            String value = optional(name);
            if (value == null) {
                return null;
            }
            try {
                int count = Integer.parseInt(value);
                if (count < 0 || count > max) {
                    throw new Invalid(name + " is out of range");
                }
                return count;
            } catch (NumberFormatException e) {
                throw new Invalid(name + " is not an integer");
            }
        }

        String json(String name, JsonMapper json) {
            String value = optional(name);
            if (value == null) {
                return null;
            }
            try {
                JsonNode node = json.readTree(value);
                return json.writeValueAsString(node);
            } catch (JacksonException e) {
                throw new Invalid(name + " is not JSON");
            }
        }

        String cropPath(String name) {
            String value = optional(name);
            if (value != null
                    && (value.length() > 300
                            || value.contains("..")
                            || !CROP_PATH.matcher(value).matches())) {
                throw new Invalid(name + " is not a relative crop path");
            }
            return value;
        }

        String text(String name, int max) {
            String value = optional(name);
            if (value != null && value.length() > max) {
                throw new Invalid(name + " is too long");
            }
            return value;
        }

        String shortText(String name) {
            String value = optional(name);
            if (value != null && !SHORT_TEXT.matcher(value).matches()) {
                throw new Invalid(name + " is malformed");
            }
            return value;
        }
    }
}
