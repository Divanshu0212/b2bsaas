"use client";

import { useState } from "react";
import { Bell } from "lucide-react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { notificationsApi } from "@/lib/api/endpoints";
import type { NotificationItem } from "@/lib/api/schema";
import { cn } from "@/lib/cn";

/** Human summary for a notification from its type + payload (backend stores payload jsonb). */
function summarize(n: NotificationItem): string {
  const p = n.payload as Record<string, unknown>;
  switch (n.type) {
    case "HOT_LEAD":
      return `Hot lead: ${(p.leadName as string) ?? "a lead"} crossed the score threshold`;
    case "DEAL_STAGE_CHANGED":
      return `Deal moved to ${(p.toStage as string) ?? "a new stage"}`;
    default:
      return n.type.replaceAll("_", " ").toLowerCase();
  }
}

export function NotificationBell() {
  const [open, setOpen] = useState(false);
  const qc = useQueryClient();

  // Poll every 20s (T6.7 / risk note: don't hammer the backend the async design protects).
  const { data } = useQuery({
    queryKey: ["notifications"],
    queryFn: () => notificationsApi.list(),
    refetchInterval: 20_000,
  });

  const markRead = useMutation({
    mutationFn: (id: string) => notificationsApi.markRead(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["notifications"] }),
  });

  const unread = data?.unreadCount ?? 0;
  const items = data?.items ?? [];

  return (
    <div className="relative">
      <button
        className="relative rounded-md p-2 text-muted hover:bg-hairline/50 hover:text-ink"
        onClick={() => setOpen((o) => !o)}
        aria-label={`Notifications${unread ? `, ${unread} unread` : ""}`}
        aria-haspopup="menu"
        aria-expanded={open}
      >
        <Bell size={18} aria-hidden />
        {unread > 0 && (
          <span className="absolute -right-0.5 -top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-danger px-1 text-[10px] font-semibold text-white tabular">
            {unread > 9 ? "9+" : unread}
          </span>
        )}
      </button>

      {open && (
        <div
          className="absolute right-0 mt-1 w-80 rounded-md border border-hairline bg-surface shadow-lg"
          role="menu"
        >
          <div className="border-b border-hairline px-4 py-2 text-sm font-medium">
            Notifications
          </div>
          {items.length === 0 ? (
            <p className="px-4 py-6 text-center text-sm text-muted">You're all caught up.</p>
          ) : (
            <ul className="max-h-96 overflow-auto">
              {items.map((n) => (
                <li key={n.id}>
                  <button
                    className={cn(
                      "flex w-full flex-col items-start gap-0.5 px-4 py-3 text-left text-sm hover:bg-hairline/40",
                      !n.readAt && "bg-accent-soft/40",
                    )}
                    onClick={() => !n.readAt && markRead.mutate(n.id)}
                  >
                    <span className="text-ink">{summarize(n)}</span>
                    <span className="text-xs text-faint tabular">
                      {new Date(n.createdAt).toLocaleString()}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
