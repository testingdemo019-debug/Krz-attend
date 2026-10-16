package com.krztechnologies.attend.common.settings;

/**
 * Published after a settings area was saved (listeners react after commit).
 *
 * @param area the app_setting key
 */
public record SettingsChangedEvent(String area) {}
