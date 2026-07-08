package com.salespipe;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModuleBoundaryTest {
    @Test
    void modulesRespectBoundaries() {
        ApplicationModules.of(SalesPipeApplication.class).verify();
    }
}
