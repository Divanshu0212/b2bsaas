import { describe, it, expect } from "vitest";
import { scoreBand, money } from "@/lib/format";

describe("scoreBand", () => {
  it("bands scores against the hot-lead threshold (0.75)", () => {
    expect(scoreBand(0.9).label).toBe("Hot");
    expect(scoreBand(0.75).label).toBe("Hot");
    expect(scoreBand(0.6).label).toBe("Warm");
    expect(scoreBand(0.2).label).toBe("Cold");
    expect(scoreBand(null).label).toBe("—");
  });
});

describe("money", () => {
  it("formats an amount with its currency", () => {
    expect(money(2500, "USD")).toMatch(/2,500/);
  });
  it("renders a dash for a missing amount", () => {
    expect(money(null, "USD")).toBe("—");
  });
});
