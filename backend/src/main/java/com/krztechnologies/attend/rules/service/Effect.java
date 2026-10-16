package com.krztechnologies.attend.rules.service;

/** The punch effect of a crossing (stored in {@code crossing.effect}). */
public enum Effect {
    CHECK_IN,
    CHECK_OUT,
    MOVEMENT,
    IGNORED_DUPLICATE,
    IGNORED_TURNED_BACK,
    IGNORED_UNKNOWN,
    PENDING_REVIEW,
    VOIDED
}
