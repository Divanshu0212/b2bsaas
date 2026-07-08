package com.salespipe;

import com.salespipe.support.PostgresRedisTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationIT extends PostgresRedisTestBase {

    @Autowired JdbcTemplate jdbc;

    @Test
    void schemaMigratesAndCoreTablesExist() {
        Integer tables = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables " +
            "WHERE table_schema='public' AND table_name IN " +
            "('organizations','users','refresh_tokens','accounts','contacts'," +
            "'leads','deal_stages','deals','deal_stage_history','audit_log')",
            Integer.class);
        assertThat(tables).isEqualTo(10);
    }
}
