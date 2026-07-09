package com.salespipe.common.retention;

import java.time.YearMonth;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * T4.7 retention: drops {@code activities} / {@code email_events} partitions older than the
 * configured window (overview change #12, "partition-drop retention job"). Dropping a whole
 * partition is O(1) metadata — far cheaper than a bulk {@code DELETE} — which is the reason
 * these two high-volume tables were partitioned monthly by {@code created_at} in the first
 * place (see V4/V5 migrations).
 *
 * <p>Only active when {@code app.retention.enabled=true} — partition drops are destructive
 * and should be turned on deliberately per environment. Partition names follow the
 * {@code <table>_YYYY_MM} convention created by {@code create_*_partition()}; the
 * {@code *_default} catch-all partition is never dropped.
 */
@Component
@ConditionalOnProperty(name = "app.retention.enabled", havingValue = "true")
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final JdbcTemplate jdbc;
    private final RetentionProperties properties;

    public RetentionJob(JdbcTemplate jdbc, RetentionProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /** Daily sweep (02:15) dropping any partition whose month is past its table's window. */
    @Scheduled(cron = "${app.retention.cron:0 15 2 * * *}")
    public void dropExpiredPartitions() {
        dropOlderThan("activities", properties.getActivitiesMonths());
        dropOlderThan("email_events", properties.getEmailEventsMonths());
    }

    /**
     * Drops every {@code <table>_YYYY_MM} partition whose month is strictly older than
     * {@code months} ago. Package-visible so a test can invoke it directly rather than
     * waiting on the cron.
     */
    int dropOlderThan(String table, int months) {
        YearMonth cutoff = YearMonth.now().minusMonths(months);
        List<String> partitions = jdbc.queryForList(
            """
            SELECT c.relname
            FROM pg_inherits i
            JOIN pg_class c   ON c.oid = i.inhrelid
            JOIN pg_class p   ON p.oid = i.inhparent
            WHERE p.relname = ?
            """,
            String.class, table);

        int dropped = 0;
        for (String partition : partitions) {
            YearMonth month = parseMonth(partition, table);
            if (month != null && month.isBefore(cutoff)) {
                jdbc.execute("DROP TABLE IF EXISTS " + partition);
                log.info("Retention: dropped partition {} (older than {} months)", partition, months);
                dropped++;
            }
        }
        return dropped;
    }

    /** Parses {@code <table>_YYYY_MM} -> YearMonth; null for the {@code _default} partition or an unexpected name. */
    private static YearMonth parseMonth(String partition, String table) {
        String prefix = table + "_";
        if (!partition.startsWith(prefix)) {
            return null;
        }
        String suffix = partition.substring(prefix.length()); // e.g. "2026_03" or "default"
        String[] parts = suffix.split("_");
        if (parts.length != 2) {
            return null;
        }
        try {
            return YearMonth.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
