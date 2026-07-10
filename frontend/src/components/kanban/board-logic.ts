import type { StageColumn, DealResponse } from "@/lib/api/schema";

/**
 * Pure board reducer (kept separate from the React component so it's unit-testable, T6.11).
 * Moving a card is optimistic: remove it from its source column and append to the target,
 * returning a NEW columns array (no mutation) so React re-renders and a rollback can restore
 * the previous snapshot verbatim on a 409.
 */
export function moveCard(
  columns: StageColumn[],
  dealId: string,
  toStageId: string,
): StageColumn[] {
  let card: DealResponse | undefined;
  // First pass: pull the card out of whatever column holds it.
  const without = columns.map((col) => {
    const idx = col.deals.findIndex((d) => d.id === dealId);
    if (idx === -1) return col;
    card = col.deals[idx];
    return { ...col, deals: col.deals.filter((d) => d.id !== dealId) };
  });
  if (!card) return columns; // unknown card: no-op
  const moved: DealResponse = { ...card, stageId: toStageId };
  // Second pass: drop it into the target column.
  return without.map((col) =>
    col.stageId === toStageId ? { ...col, deals: [...col.deals, moved] } : col,
  );
}

/** Find which stage a deal currently sits in (used to detect no-op drops). */
export function stageOf(columns: StageColumn[], dealId: string): string | null {
  for (const col of columns) {
    if (col.deals.some((d) => d.id === dealId)) return col.stageId;
  }
  return null;
}
