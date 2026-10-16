package com.krztechnologies.attend.notification.domain;

/**
 * An e-mail after template rendering (escaped HTML and plain text; never images of faces).
 *
 * @param templateKey the template it came from (e.g. {@code notification}, {@code test})
 */
public record RenderedEmail(String templateKey, String subject, String html, String text) {}
