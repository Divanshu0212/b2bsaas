package com.salespipe.admin;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T4.7: the GDPR tenant-delete endpoint is ADMIN-only. A MANAGER is forbidden; an ADMIN is
 * allowed. Slice test with method security enabled and the service mocked.
 */
@WebMvcTest(controllers = TenantDeletionController.class)
@Import(TenantDeletionControllerSecurityTest.MethodSecurity.class)
class TenantDeletionControllerSecurityTest {

    @EnableMethodSecurity
    static class MethodSecurity {}

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    TenantDeletionService service;

    @Test
    void nonAdminIsForbidden() throws Exception {
        mockMvc.perform(delete("/admin/tenants/" + UUID.randomUUID())
                .with(user("mgr").roles("MANAGER")).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
            .andExpect(status().isForbidden());
    }

    @Test
    void adminIsAllowed() throws Exception {
        when(service.hardDelete(org.mockito.ArgumentMatchers.any())).thenReturn(Map.of("leads", 0));
        mockMvc.perform(delete("/admin/tenants/" + UUID.randomUUID())
                .with(user("boss").roles("ADMIN")).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
            .andExpect(status().isOk());
    }
}
