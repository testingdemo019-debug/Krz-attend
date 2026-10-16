package com.krztechnologies.attend.crossing.service;

/**
 * Ingestion created a review item or joined a crossing to an open one; after the commit the Review Queue's badges
 * refresh (the review module publishes {@code review.counts}).
 */
public record ReviewQueueChanged(String reviewCode) {}
