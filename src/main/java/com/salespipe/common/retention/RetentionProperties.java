package com.salespipe.common.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * T4.7 retention config (prefix {@code app.retention}). Documented retention windows for
 * the partitioned high-volume tables; the {@link RetentionJob} drops partitions older than
 * these windows.
 */
@ConfigurationProperties(prefix = "app.retention")
public class RetentionProperties {

    /** Off by default — dropping partitions is destructive; opt in per environment. */
    private boolean enabled = false;
    /** Keep this many months of {@code activities} partitions. */
    private int activitiesMonths = 12;
    /** Keep this many months of {@code email_events} partitions. */
    private int emailEventsMonths = 12;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getActivitiesMonths() { return activitiesMonths; }
    public void setActivitiesMonths(int activitiesMonths) { this.activitiesMonths = activitiesMonths; }

    public int getEmailEventsMonths() { return emailEventsMonths; }
    public void setEmailEventsMonths(int emailEventsMonths) { this.emailEventsMonths = emailEventsMonths; }
}
