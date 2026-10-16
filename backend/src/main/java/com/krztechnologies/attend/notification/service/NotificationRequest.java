package com.krztechnologies.attend.notification.service;

import java.util.Map;

/**
 * What to tell people.
 *
 * @param link app-relative path the notification opens (e.g. {@code /settings/users?tab=requests}), or null
 * @param dedupeKey a recipient gets at most one notification per key (e.g. {@code NR-06:AR-0001}); null = no dedupe
 * @param params extra template variables for the e-mail
 */
public record NotificationRequest(
        String title, String body, String link, String dedupeKey, Map<String, Object> params) {

    public NotificationRequest {
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
