"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { notificationsApi } from "@/lib/api/endpoints";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/cn";

export default function NotificationsPage() {
  const qc = useQueryClient();
  const { data, isLoading } = useQuery({
    queryKey: ["notifications"],
    queryFn: () => notificationsApi.list(200),
    refetchInterval: 20_000,
  });
  const markRead = useMutation({
    mutationFn: (id: string) => notificationsApi.markRead(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["notifications"] }),
  });

  return (
    <div className="mx-auto max-w-2xl">
      <h1 className="mb-4 text-xl font-semibold tracking-tight">Notifications</h1>
      {isLoading ? (
        <p className="text-sm text-muted">Loading…</p>
      ) : !data || data.items.length === 0 ? (
        <p className="rounded-md border border-hairline bg-surface p-8 text-center text-sm text-muted">
          Nothing here yet. Hot-lead and stage-change alerts show up as they happen.
        </p>
      ) : (
        <ul className="divide-y divide-hairline rounded-md border border-hairline bg-surface">
          {data.items.map((n) => (
            <li
              key={n.id}
              className={cn(
                "flex items-center justify-between gap-4 px-4 py-3",
                !n.readAt && "bg-accent-soft/40",
              )}
            >
              <div>
                <p className="text-sm text-ink">{n.type.replaceAll("_", " ").toLowerCase()}</p>
                <p className="text-xs text-faint tabular">
                  {new Date(n.createdAt).toLocaleString()}
                </p>
              </div>
              {!n.readAt && (
                <Button size="sm" variant="outline" onClick={() => markRead.mutate(n.id)}>
                  Mark read
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
