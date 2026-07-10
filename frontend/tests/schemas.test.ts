import { describe, it, expect } from "vitest";
import { leadFormSchema, accountFormSchema, dealFormSchema } from "@/lib/forms/schemas";

describe("leadFormSchema", () => {
  it("normalizes empty optional strings to null", () => {
    const out = leadFormSchema.parse({
      status: "NEW",
      source: "",
      rawNotes: "",
      contactId: "",
      accountId: "",
      ownerId: "",
    });
    expect(out.source).toBeNull();
    expect(out.accountId).toBeNull();
  });

  it("rejects an invalid status (same rule as backend @NotNull enum)", () => {
    expect(leadFormSchema.safeParse({ status: "BOGUS" }).success).toBe(false);
  });

  it("rejects a non-UUID account id", () => {
    const r = leadFormSchema.safeParse({ status: "NEW", accountId: "not-a-uuid" });
    expect(r.success).toBe(false);
  });
});

describe("accountFormSchema", () => {
  it("requires a name (mirrors @NotBlank)", () => {
    expect(accountFormSchema.safeParse({ name: "" }).success).toBe(false);
    expect(accountFormSchema.safeParse({ name: "Acme" }).success).toBe(true);
  });

  it("coerces employeeCount and rejects non-positive", () => {
    expect(accountFormSchema.safeParse({ name: "A", employeeCount: "0" }).success).toBe(false);
    const ok = accountFormSchema.parse({ name: "A", employeeCount: "50" });
    expect(ok.employeeCount).toBe(50);
  });
});

describe("dealFormSchema", () => {
  it("requires a stage UUID", () => {
    expect(dealFormSchema.safeParse({ stageId: "" }).success).toBe(false);
  });
  it("accepts a valid stage and coerces amount", () => {
    const out = dealFormSchema.parse({
      stageId: "b3f1c2d4-5e6a-4b7c-8d9e-0f1a2b3c4d5e",
      amount: "2500",
    });
    expect(out.amount).toBe(2500);
  });
});
