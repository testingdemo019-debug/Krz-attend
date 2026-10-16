package com.krztechnologies.attend.system.service;

import com.krztechnologies.attend.common.app.AppProperties;
import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.feature.FeatureFlags;
import com.krztechnologies.attend.common.graph.EntraProperties;
import com.krztechnologies.attend.common.settings.SettingsArea;
import com.krztechnologies.attend.common.settings.SettingsService;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.notification.service.MailProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.boot.SpringBootVersion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Settings → System → Download diagnostics: a zip with versions, every settings area and integration switch (never a
 * secret: secrets live only in deploy/.env and are not part of any settings record), the service status rows and the
 * last 200 WARN/ERROR lines of the backend log, each masked by {@link LogSanitiser}. No personal data. Audited.
 */
@Service
public class DiagnosticsService {

    static final int LOG_LINES = 200;
    /** Only the end of today's log file is searched, so a huge file never loads into memory. */
    private static final long LOG_TAIL_BYTES = 8L * 1024 * 1024;

    private static final Pattern WARN_OR_ERROR =
            Pattern.compile("\"level\"\\s*:\\s*\"(WARN|ERROR)\"|^\\S+\\s+\\S+\\s+(WARN|ERROR)\\b");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm");
    private static final List<SettingsArea<?>> AREAS = List.of(
            SettingsArea.GENERAL,
            SettingsArea.SIGN_IN,
            SettingsArea.ATTENDANCE_RULES,
            SettingsArea.ENROLMENT,
            SettingsArea.PRIVACY,
            SettingsArea.HRMS);

    private final SettingsService settings;
    private final SystemStatusService status;
    private final AppProperties app;
    private final FeatureFlags features;
    private final MailProperties mail;
    private final EntraProperties entra;
    private final TimeService time;
    private final AuditService audit;
    private final JsonMapper json;
    private final LogSanitiser sanitiser = new LogSanitiser();

    public DiagnosticsService(
            SettingsService settings,
            SystemStatusService status,
            AppProperties app,
            FeatureFlags features,
            MailProperties mail,
            EntraProperties entra,
            TimeService time,
            AuditService audit,
            JsonMapper json) {
        this.settings = settings;
        this.status = status;
        this.app = app;
        this.features = features;
        this.mail = mail;
        this.entra = entra;
        this.time = time;
        this.audit = audit;
        this.json = json;
    }

    /** The download: its file name and the zip bytes. */
    public record Diagnostics(String fileName, byte[] zip) {}

    @Transactional
    public Diagnostics build() {
        String fileName = "krz-attend_diagnostics_" + time.zoned(time.now()).format(FILE_STAMP) + ".zip";
        List<String> logLines = warnAndErrorLines();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            add(zip, "README.txt", readme().getBytes(StandardCharsets.UTF_8));
            add(zip, "versions.json", pretty(versions()));
            add(zip, "settings.json", pretty(settingsWithoutSecrets()));
            add(zip, "status.json", pretty(status.status()));
            add(zip, "logs/backend-warn-error.log", String.join("\n", logLines).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not build the diagnostics zip", e);
        }
        audit.record(
                AuditCategory.SYSTEM,
                "Downloaded diagnostics",
                "System",
                null,
                null,
                Map.of("file", fileName, "logLines", logLines.size()));
        return new Diagnostics(fileName, bytes.toByteArray());
    }

    private Map<String, Object> versions() {
        Map<String, Object> versions = new LinkedHashMap<>();
        versions.put("appVersion", app.version());
        versions.put("environment", app.env().name());
        versions.put("java", Runtime.version().toString());
        versions.put("springBoot", SpringBootVersion.getVersion());
        versions.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        versions.put("generatedAt", time.now().toString());
        return versions;
    }

    /** The settings areas as saved, plus integration switches; secrets are only ever reported as "set or not". */
    private Map<String, Object> settingsWithoutSecrets() {
        Map<String, Object> all = new LinkedHashMap<>();
        for (SettingsArea<?> area : AREAS) {
            var current = settings.get(area);
            all.put(area.key(), Map.of("value", current.value(), "version", current.version()));
        }
        Map<String, Object> integrations = new LinkedHashMap<>();
        integrations.put("mailEnabled", features.mailEnabled());
        integrations.put("mailProvider", mail.provider().name());
        integrations.put("mailTestRedirect", mail.testRecipient() != null);
        integrations.put("pushEnabled", features.pushEnabled());
        integrations.put("hrmsEnabled", features.hrmsEnabled());
        integrations.put("entraSyncScope", entra.syncScope().name());
        integrations.put("entraSecretExpires", entra.clientSecretExpires());
        all.put("integrations", integrations);
        return all;
    }

    /** The last {@link #LOG_LINES} WARN/ERROR lines of today's backend log (production writes the file), masked. */
    private List<String> warnAndErrorLines() {
        Path file = app.dataPath().resolve("logs").resolve("backend").resolve("backend.log");
        if (!Files.isRegularFile(file)) {
            return List.of("No log file at logs/backend/backend.log (outside production the log goes to the console).");
        }
        byte[] tail;
        long skipped;
        try (InputStream in = Files.newInputStream(file)) {
            skipped = Math.max(0, Files.size(file) - LOG_TAIL_BYTES);
            in.skipNBytes(skipped);
            tail = in.readAllBytes();
        } catch (IOException e) {
            return List.of("The log file could not be read (" + e.getClass().getSimpleName() + ").");
        }
        String[] lines = new String(tail, StandardCharsets.UTF_8).split("\r?\n");
        Deque<String> last = new ArrayDeque<>();
        // After a skip the first line is only the end of a line.
        for (int i = skipped > 0 ? 1 : 0; i < lines.length; i++) {
            if (WARN_OR_ERROR.matcher(lines[i]).find()) {
                last.addLast(sanitiser.mask(lines[i]));
                if (last.size() > LOG_LINES) {
                    last.removeFirst();
                }
            }
        }
        return List.copyOf(last);
    }

    private static String readme() {
        return """
                KRZ Attend diagnostics
                versions.json                 application, Java and Spring Boot versions
                settings.json                 every settings area and the integration switches (no secrets)
                status.json                   the service status rows of Settings -> System
                logs/backend-warn-error.log   the last %d WARN/ERROR lines of the backend log, masked
                No personal data and no secrets are included.
                """.formatted(LOG_LINES);
    }

    private byte[] pretty(Object value) {
        return json.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
    }

    private static void add(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }
}
