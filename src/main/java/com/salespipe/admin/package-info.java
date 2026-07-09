/**
 * Cross-cutting platform admin operations (T4.7): GDPR tenant hard-delete. Deletes rows
 * across every org-scoped table directly via {@code JdbcTemplate} (not through other
 * modules' repositories) — the delete is a raw data operation spanning all tables, so it
 * deliberately doesn't couple to any single domain module's persistence beans.
 */
@org.springframework.modulith.ApplicationModule
package com.salespipe.admin;
