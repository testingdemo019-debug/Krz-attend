package com.krztechnologies.attend.common.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.encoder.EncoderBase;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Wraps any Logback encoder (readable pattern in development, JSON in production) and masks anything that looks like
 * a secret before it is written: {@code password=…}, {@code secret=…}, tokens and API keys, bearer tokens and the
 * credentials in RTSP URLs ({@code rtsp://***@host}). A last line of defence: code never logs secrets on purpose.
 */
public class MaskingEncoder extends EncoderBase<ILoggingEvent> {

    private static final List<Rule> RULES = List.of(
            new Rule(
                    Pattern.compile(
                            "(?i)((?:password|passwd|pwd|secret|token|api[_-]?key|client[_-]?secret)[\"']?\\s*[=:]\\s*[\"']?)[^\\s\"'&,;}]+"),
                    "$1***"),
            new Rule(Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+"), "$1***"),
            // Up to the last "@" before the path: passwords may contain "@".
            new Rule(Pattern.compile("(?i)(rtsps?://)[^\\s/]+@"), "$1***@"));

    private Encoder<ILoggingEvent> delegate;

    /** The wrapped encoder (set from logback-spring.xml). */
    public void setDelegate(Encoder<ILoggingEvent> delegate) {
        this.delegate = delegate;
    }

    @Override
    public void start() {
        if (delegate == null) {
            addError("MaskingEncoder needs a <delegate> encoder");
            return;
        }
        delegate.setContext(getContext());
        delegate.start();
        super.start();
    }

    @Override
    public void stop() {
        if (delegate != null) {
            delegate.stop();
        }
        super.stop();
    }

    @Override
    public byte[] headerBytes() {
        return delegate.headerBytes();
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        return mask(new String(delegate.encode(event), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public byte[] footerBytes() {
        return delegate.footerBytes();
    }

    static String mask(String line) {
        String masked = line;
        for (Rule rule : RULES) {
            masked = rule.pattern().matcher(masked).replaceAll(rule.replacement());
        }
        return masked;
    }

    private record Rule(Pattern pattern, String replacement) {}
}
