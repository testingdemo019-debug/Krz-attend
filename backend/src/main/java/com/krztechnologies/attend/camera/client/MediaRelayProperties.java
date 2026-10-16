package com.krztechnologies.attend.camera.client;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The go2rtc media relay's API (MEDIA_RELAY_URL, GO2RTC_API_USER, GO2RTC_API_PASSWORD in deploy/.env). The API needs
 * basic auth; {@link #toString()} leaves the password out.
 *
 * @param url containers: http://media-relay:1984; the dev profile on the host: http://localhost:1984
 */
@Validated
@ConfigurationProperties("krz.media-relay")
public record MediaRelayProperties(
        @NotNull URI url, @NotBlank String user, @NotBlank String password) {

    @Override
    public String toString() {
        return "MediaRelayProperties[url=" + url + ", user=" + user + "]";
    }
}
