"use client";

import { useDroppable } from "@dnd-kit/core";
import type { StageColumn } from "@/lib/api/schema";
import { money } from "@/lib/format";
import { DealCard } from "./DealCard";
import { cn } from "@/lib/cn";

/** A pipeline stage column: a drop target for cards. Header shows the stage name + count and
 *  the column's total value (the number a rep actually watches). */
export function Column({ column }: { column: StageColumn }) {
  const { setNodeRef, isOver } = useDroppable({ id: column.stageId });
  const total = column.deals.reduce((sum, d) => sum + (d.amount ?? 0), 0);
  const currency = column.deals.find((d) => d.currency)?.currency ?? null;

  return (
    <div className="flex w-72 shrink-0 flex-col">
      <div className="mb-2 flex items-baseline justify-between px-1">
        <h2 className="text-sm font-semibold text-ink">
          {column.stageName}
          <span className="ml-2 text-xs font-normal text-faint tabular">
            {column.deals.length}
          </span>
        </h2>
        <span className="text-xs text-muted tabular">{money(total, currency)}</span>
      </div>
      <div
        ref={setNodeRef}
        className={cn(
          "flex min-h-24 flex-1 flex-col gap-2 rounded-lg border border-hairline/70 bg-hairline/20 p-2 transition-colors",
          isOver && "border-accent bg-accent-soft/50",
        )}
      >
        {column.deals.map((deal) => (
          <DealCard key={deal.id} deal={deal} />
        ))}
        {column.deals.length === 0 && (
          <p className="px-1 py-3 text-center text-xs text-faint">Drop deals here</p>
        )}
      </div>
    </div>
  );
}
