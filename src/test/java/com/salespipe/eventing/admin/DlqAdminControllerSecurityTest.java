package com.salespipe.eventing.admin;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T4.5: the DLQ admin API is ADMIN-only. A MANAGER/SALES_REP is forbidden; an ADMIN is
 * allowed. Slice test — method security ({@code @PreAuthorize}) is enabled explicitly,
 * and the service is mocked so no Kafka is needed.
 */
@WebMvcTest(controllers = DlqAdminController.class)
@Import(DlqAdminControllerSecurityTest.MethodSecurity.class)
class DlqAdminControllerSecurityTest {

    @EnableMethodSecurity
    static class MethodSecurity {}

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    DlqAdminService service;

    @Test
    void nonAdminIsForbidden() throws Exception {
        mockMvc.perform(get("/admin/dlq/topics").with(user("mgr").roles("MANAGER")))
            .andExpect(status().isForbidden());
    }

    @Test
    void adminIsAllowed() throws Exception {
        when(service.dlqTopics()).thenReturn(List.of("deal.stage.changed.DLQ"));
        mockMvc.perform(get("/admin/dlq/topics").with(user("boss").roles("ADMIN")))
            .andExpect(status().isOk());
    }
}
