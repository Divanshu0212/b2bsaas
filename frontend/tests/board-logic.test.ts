import { describe, it, expect } from "vitest";
import { moveCard, stageOf } from "@/components/kanban/board-logic";
import type { StageColumn, DealResponse } from "@/lib/api/schema";

function deal(id: string, stageId: string): DealResponse {
  return {
    id,
    stageId,
    leadId: null,
    accountId: null,
    ownerId: null,
    amount: 100,
    currency: "USD",
    expectedCloseDate: null,
    version: 0,
  };
}

function board(): StageColumn[] {
  return [
    { stageId: "s1", stageName: "New", position: 0, deals: [deal("d1", "s1"), deal("d2", "s1")] },
    { stageId: "s2", stageName: "Won", position: 1, deals: [] },
  ];
}

describe("moveCard", () => {
  it("moves a card to the target column and stamps its new stageId", () => {
    const next = moveCard(board(), "d1", "s2");
    expect(next[0].deals.map((d) => d.id)).toEqual(["d2"]);
    expect(next[1].deals.map((d) => d.id)).toEqual(["d1"]);
    expect(next[1].deals[0].stageId).toBe("s2");
  });

  it("does not mutate the input columns (rollback needs the old snapshot intact)", () => {
    const original = board();
    const snapshot = JSON.stringify(original);
    moveCard(original, "d1", "s2");
    expect(JSON.stringify(original)).toBe(snapshot);
  });

  it("is a no-op for an unknown card id", () => {
    const original = board();
    const next = moveCard(original, "nope", "s2");
    expect(next).toBe(original);
  });
});

describe("stageOf", () => {
  it("finds the stage holding a deal", () => {
    expect(stageOf(board(), "d2")).toBe("s1");
  });
  it("returns null when the deal isn't on the board", () => {
    expect(stageOf(board(), "ghost")).toBeNull();
  });
});
