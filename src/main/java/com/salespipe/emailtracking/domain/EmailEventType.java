package com.salespipe.emailtracking.domain;

/** Mirrors the {@code email_events.event_type} CHECK constraint (V5__email_tracking.sql). */
public enum EmailEventType {
    OPENED,
    CLICKED,
    BOUNCED
}
