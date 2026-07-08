package com.salespipe.common.tenant;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TenantContextTest {
    @Test
    void holdsAndReportsOrgId() {
        TenantContext ctx = new TenantContext();
        assertThat(ctx.isSet()).isFalse();
        UUID org = UUID.randomUUID();
        ctx.setOrgId(org);
        assertThat(ctx.isSet()).isTrue();
        assertThat(ctx.getOrgId()).isEqualTo(org);
    }
}
