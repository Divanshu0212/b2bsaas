package com.salespipe.common.retention;

import static org.assertj.core.api.Assertions.assertThat;

import com.salespipe.support.PostgresRedisTestBase;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T4.7: the retention job drops {@code activities} partitions older than the window and
 * keeps recent ones. Creates one old and one current partition via the migration's helper
 * function, then runs the drop.
 */
class RetentionJobIT extends PostgresRedisTestBase {

    @Autowired JdbcTemplate jdbc;

    @Test
    void dropsPartitionsPastTheWindowAndKeepsRecentOnes() {
        YearMonth old = YearMonth.now().minusMonths(24);
        YearMonth recent = YearMonth.now();
        // RETURNS void -> call as a query and discard the (empty) result.
        jdbc.query("SELECT create_activities_partition(?, ?)", rs -> null,
            old.getYear(), old.getMonthValue());
        jdbc.query("SELECT create_activities_partition(?, ?)", rs -> null,
            recent.getYear(), recent.getMonthValue());

        String oldName = String.format("activities_%04d_%02d", old.getYear(), old.getMonthValue());
        String recentName = String.format("activities_%04d_%02d", recent.getYear(), recent.getMonthValue());
        assertThat(partitionExists(oldName)).isTrue();

        RetentionProperties props = new RetentionProperties();
        props.setActivitiesMonths(12);
        RetentionJob job = new RetentionJob(jdbc, props);
        job.dropOlderThan("activities", 12);

        assertThat(partitionExists(oldName)).as("old partition dropped").isFalse();
        assertThat(partitionExists(recentName)).as("recent partition kept").isTrue();
    }

    private boolean partitionExists(String name) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pg_class WHERE relname = ?", Integer.class, name);
        return n != null && n > 0;
    }
}
