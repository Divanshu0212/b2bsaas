import { defineConfig, devices } from "@playwright/test";

/**
 * E2E runs against a REAL running stack (frontend + backend + infra), not a mock — same
 * Testcontainers discipline as the Java side. CI brings the stack up (docker compose) and
 * sets E2E_BASE_URL; locally it defaults to the dev server on :3000.
 */
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: "list",
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3000",
    trace: "on-first-retry",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
