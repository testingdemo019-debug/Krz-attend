package com.krztechnologies.attend.camera.domain;

import java.nio.charset.StandardCharsets;

/**
 * The RTSP URL of a camera stream, built in memory only (security §12). {@link #masked()} (also {@link #toString()})
 * shows {@code rtsp://user:••••@host:554/path} like the prototype, whether or not a password is set, so a URL can be
 * displayed or logged without the password; {@link #withCredentials()} is for go2rtc only. User name and password
 * are percent-encoded.
 */
public final class RtspUrl {

    private static final String MASK = "••••";

    private final String host;
    private final int port;
    private final String path;
    private final String username;
    private final String password;

    private RtspUrl(String host, int port, String path, String username, String password) {
        this.host = host;
        this.port = port;
        this.path = path;
        this.username = username == null || username.isEmpty() ? null : username;
        this.password = password == null || password.isEmpty() ? null : password;
    }

    /**
     * @param username null when the camera needs no sign-in
     * @param password the decrypted password, null when none is set (or not needed, e.g. for {@link #masked()})
     */
    public static RtspUrl of(String host, int port, String path, String username, String password) {
        return new RtspUrl(host, port, path, username, password);
    }

    public String withCredentials() {
        return build(password == null ? null : encode(password));
    }

    public String masked() {
        return build(MASK);
    }

    @Override
    public String toString() {
        return masked();
    }

    private String build(String secret) {
        StringBuilder url = new StringBuilder("rtsp://");
        if (username != null) {
            url.append(encode(username));
            if (secret != null) {
                url.append(':').append(secret);
            }
            url.append('@');
        }
        return url.append(host).append(':').append(port).append(path).toString();
    }

    /** RFC 3986 userinfo: everything but the unreserved characters is percent-encoded (UTF-8). */
    static String encode(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            boolean unreserved = (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '.'
                    || c == '_'
                    || c == '~';
            if (unreserved) {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return out.toString();
    }
}
