"use client";

import { useState } from "react";
import {
  DndContext,
  DragOverlay,
  PointerSensor,
  useSensor,
  useSensors,
  closestCorners,
  type DragStartEvent,
  type DragEndEvent,
} from "@dnd-kit/core";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { dealsApi } from "@/lib/api/endpoints";
import { ApiError } from "@/lib/api/client";
import { useToast } from "@/components/ui/toast";
import type { StageColumn, DealResponse } from "@/lib/api/schema";
import { moveCard, stageOf } from "./board-logic";
import { Column } from "./Column";
import { DealCard } from "./DealCard";

const KEY = ["pipeline"];

export function Board() {
  const qc = useQueryClient();
  const { toast } = useToast();
  const [activeDeal, setActiveDeal] = useState<DealResponse | null>(null);

  const { data: columns, isLoading, isError } = useQuery({
    queryKey: KEY,
    queryFn: () => dealsApi.pipeline(),
  });

  // A small drag distance guard so a click on the "View lead" link isn't read as a drag.
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 5 } }));

  function findDeal(cols: StageColumn[], id: string): DealResponse | null {
    for (const c of cols) {
      const d = c.deals.find((x) => x.id === id);
      if (d) return d;
    }
    return null;
  }

  function onDragStart(e: DragStartEvent) {
    if (columns) setActiveDeal(findDeal(columns, String(e.active.id)));
  }

  async function onDragEnd(e: DragEndEvent) {
    setActiveDeal(null);
    const dealId = String(e.active.id);
    const toStageId = e.over ? String(e.over.id) : null;
    const current = qc.getQueryData<StageColumn[]>(KEY);
    if (!current || !toStageId) return;

    const from = stageOf(current, dealId);
    if (!from || from === toStageId) return; // no-op drop

    const deal = findDeal(current, dealId);
    if (!deal) return;

    // Optimistic move: update the cache immediately so the card jumps columns.
    const snapshot = current;
    qc.setQueryData<StageColumn[]>(KEY, moveCard(current, dealId, toStageId));

    try {
      await dealsApi.changeStage(dealId, { toStageId, version: deal.version });
      // Refetch to pick up the server-incremented version (so the next drag isn't stale).
      qc.invalidateQueries({ queryKey: KEY });
    } catch (err) {
      // Roll the card back to its server-confirmed column, then explain why (T6.5 conflict).
      qc.setQueryData<StageColumn[]>(KEY, snapshot);
      if (err instanceof ApiError && err.status === 409) {
        toast("Someone else moved this deal. Put it back — reload to see the latest.", "error");
      } else {
        toast("Couldn't move the deal. Try again.", "error");
      }
    }
  }

  if (isLoading) return <BoardSkeleton />;
  if (isError || !columns)
    return <p className="text-sm text-danger">Couldn&apos;t load the pipeline.</p>;

  return (
    <DndContext
      sensors={sensors}
      collisionDetection={closestCorners}
      onDragStart={onDragStart}
      onDragEnd={onDragEnd}
    >
      <div className="flex gap-4 overflow-x-auto pb-4">
        {columns.map((col) => (
          <Column key={col.stageId} column={col} />
        ))}
      </div>
      <DragOverlay>{activeDeal ? <DealCard deal={activeDeal} dragging /> : null}</DragOverlay>
    </DndContext>
  );
}

function BoardSkeleton() {
  return (
    <div className="flex gap-4">
      {[0, 1, 2, 3].map((i) => (
        <div key={i} className="w-72 shrink-0 space-y-2">
          <div className="mb-3 h-6 w-full border-b-2 border-hairline" />
          <div className="h-20 bg-hairline/40" />
          <div className="h-20 bg-hairline/40" />
        </div>
      ))}
    </div>
  );
}
