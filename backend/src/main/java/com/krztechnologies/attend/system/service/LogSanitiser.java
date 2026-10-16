package com.krztechnologies.attend.system.service;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.encoder.EncoderBase;
import com.krztechnologies.attend.common.logging.MaskingEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Masks a log line before it leaves the server in a diagnostics download: the production {@link MaskingEncoder} (the
 * same rules as the log files: passwords, secrets, tokens, bearer tokens, RTSP credentials), then e-mail addresses,
 * which never belong in a log but must not leave the server if one slipped in.
 */
final class LogSanitiser {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+)");

    private final MaskingEncoder encoder = new MaskingEncoder();

    LogSanitiser() {
        encoder.setDelegate(new MessageOnly());
        encoder.start();
    }

    String mask(String line) {
        LoggingEvent event = new LoggingEvent();
        event.setMessage(line);
        String masked = new String(encoder.encode(event), StandardCharsets.UTF_8);
        return EMAIL.matcher(masked).replaceAll("***@$1");
    }

    /** Hands the raw line to the masking rules unchanged (no pattern, no placeholders). */
    private static final class MessageOnly extends EncoderBase<ILoggingEvent> {
        @Override
        public byte[] headerBytes() {
            return new byte[0];
        }

        @Override
        public byte[] encode(ILoggingEvent event) {
            return event.getMessage().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public byte[] footerBytes() {
            return new byte[0];
        }
    }
}
