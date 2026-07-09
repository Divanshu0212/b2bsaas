package com.salespipe.common.metrics;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.salespipe.support.PostgresRedisTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T4.1: confirms the Prometheus registry dep + {@code management.endpoints} exposure
 * actually produce scrape-able output, including an auto-bound Hikari pool meter (the
 * app has a real {@code DataSource} via {@link PostgresRedisTestBase}).
 */
@AutoConfigureMockMvc
class PrometheusEndpointIT extends PostgresRedisTestBase {

    @Autowired
    MockMvc mockMvc;

    @Test
    void prometheusEndpointExposesJvmAndCustomMeters() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("jvm_memory_used_bytes")))
            .andExpect(content().string(containsString("hikaricp_connections")));
    }
}
