"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { notificationsApi } from "@/lib/api/endpoints";
import { Button } from "@/components/ui/button";
import { PageHeader } from "@/components/ui/page-header";
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
      <PageHeader eyebrow="Alerts" title="Notifications" />
      {isLoading ? (
        <p className="text-sm text-muted">Loading…</p>
      ) : !data || data.items.length === 0 ? (
        <p className="border-t border-hairline py-16 text-center text-sm text-muted">
          Nothing here yet. Hot-lead and stage-change alerts show up as they happen.
        </p>
      ) : (
        <ul className="divide-y divide-hairline border-t border-hairline">
          {data.items.map((n) => (
            <li
              key={n.id}
              className={cn(
                "flex items-center justify-between gap-4 px-1 py-4",
                !n.readAt && "border-l-2 border-l-accent bg-accent-soft/30 pl-3",
              )}
            >
              <div>
                <p className="text-sm capitalize text-ink">{n.type.replaceAll("_", " ").toLowerCase()}</p>
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
