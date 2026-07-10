"use client";

import { useDraggable } from "@dnd-kit/core";
import Link from "next/link";
import type { DealResponse } from "@/lib/api/schema";
import { money } from "@/lib/format";
import { cn } from "@/lib/cn";

/** A draggable deal card. Amount is the headline (tabular so columns align); the lead link
 *  lets the rep jump to detail. Score badge is shown when the card carries a score. */
export function DealCard({ deal, dragging }: { deal: DealResponse; dragging?: boolean }) {
  const { attributes, listeners, setNodeRef, transform, isDragging } = useDraggable({
    id: deal.id,
  });

  const style = transform
    ? { transform: `translate3d(${transform.x}px, ${transform.y}px, 0)` }
    : undefined;

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={cn(
        "border border-hairline bg-surface p-3 transition-colors hover:border-ink",
        "cursor-grab active:cursor-grabbing",
        (isDragging || dragging) && "opacity-70 border-accent shadow-[0_8px_24px_-12px_rgba(23,19,14,0.35)]",
      )}
      {...listeners}
      {...attributes}
    >
      <div className="flex items-start justify-between gap-2">
        <span className="tabular display text-lg text-ink">
          {money(deal.amount, deal.currency)}
        </span>
      </div>
      {deal.leadId && (
        <Link
          href={`/leads/${deal.leadId}`}
          className="mt-1.5 block text-xs text-accent underline-offset-4 hover:underline"
          // Don't start a drag when the link is clicked.
          onPointerDown={(e) => e.stopPropagation()}
        >
          View lead
        </Link>
      )}
    </div>
  );
}
