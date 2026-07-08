package com.salespipe.common.tenant;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

class TenantIsolationArchTest {

    private final JavaClasses classes = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.salespipe");

    @Test
    void everyOrgScopedEntityExtendsTenantEntity() {
        classes()
            .that().areAnnotatedWith(jakarta.persistence.Entity.class)
            .and().haveSimpleNameNotEndingWith("Organization")
            .and().haveSimpleNameNotEndingWith("AuditLog")
            .and().haveSimpleNameNotEndingWith("RefreshToken")
            .should().beAssignableTo(TenantEntity.class)
            .because("org-scoped entities must inherit the tenant @Filter; " +
                     "Organization/AuditLog/RefreshToken are handled explicitly")
            .allowEmptyShould(true)
            .check(classes);
    }
}
