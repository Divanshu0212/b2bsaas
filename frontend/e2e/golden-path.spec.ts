import { test, expect } from "@playwright/test";

/**
 * Golden path (T6.11): register a fresh org → land on the pipeline → open the leads list →
 * create a lead → see it in detail with a score panel. Runs against a real containerized
 * backend in CI. Each run uses a unique email so it's idempotent across retries.
 */
test("register, view pipeline, create + open a lead", async ({ page }) => {
  const email = `e2e+${Date.now()}@salespipe.test`;

  // --- register (first-run creates the org; caller becomes ADMIN) ---
  await page.goto("/login");
  await page.getByRole("button", { name: /create a new workspace/i }).click();
  await page.getByLabel("Organization").fill("E2E Co");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill("password123");
  await page.getByRole("button", { name: /create workspace/i }).click();

  // --- pipeline board loads (default seeded stages render as columns) ---
  await expect(page).toHaveURL(/\/pipeline/);
  await expect(page.getByRole("heading", { name: "Pipeline" })).toBeVisible();

  // --- create a lead ---
  await page.getByRole("link", { name: "Leads" }).click();
  await expect(page).toHaveURL(/\/leads/);
  await page.getByRole("link", { name: "New lead" }).click();
  await page.getByLabel("Source").fill("e2e");
  await page.getByRole("button", { name: /create lead/i }).click();

  // --- lands on the lead detail with the score panel present ---
  await expect(page).toHaveURL(/\/leads\/[0-9a-f-]+/);
  await expect(page.getByText("Lead score")).toBeVisible();
  await expect(page.getByRole("heading", { name: "Timeline" })).toBeVisible();
});
