"use client";

import { useInfiniteQuery } from "@tanstack/react-query";
import { Mail, MousePointerClick, UserPlus, ArrowRightLeft, Activity } from "lucide-react";
import { leadsApi } from "@/lib/api/endpoints";
import { Button } from "@/components/ui/button";
import type { ActivityResponse } from "@/lib/api/schema";

const ICONS: Record<string, typeof Activity> = {
  LEAD_CREATED: UserPlus,
  ACTIVITY_LOGGED: Activity,
  DEAL_STAGE_CHANGED: ArrowRightLeft,
  EMAIL_OPENED: Mail,
  EMAIL_CLICKED: MousePointerClick,
};

function label(a: ActivityResponse): string {
  const p = a.payload as Record<string, unknown>;
  switch (a.activityType) {
    case "LEAD_CREATED":
      return "Lead created";
    case "DEAL_STAGE_CHANGED":
      return `Deal moved to ${(p.toStage as string) ?? "a new stage"}`;
    case "EMAIL_OPENED":
      return "Opened an email";
    case "EMAIL_CLICKED":
      return "Clicked a link";
    default:
      return a.activityType.replaceAll("_", " ").toLowerCase();
  }
}

/**
 * Merged activity timeline (T6.6). Uses TanStack's infinite query over the backend's
 * page-based endpoint; pages are keyed by index so entries never duplicate or drop across
 * "Load more". `last` from the Spring page tells us when to stop.
 */
export function TimelineFeed({ leadId }: { leadId: string }) {
  const { data, fetchNextPage, hasNextPage, isFetchingNextPage, isLoading, isError } =
    useInfiniteQuery({
      queryKey: ["timeline", leadId],
      queryFn: ({ pageParam }) => leadsApi.timeline(leadId, pageParam),
      initialPageParam: 0,
      getNextPageParam: (lastPage) => (lastPage.last ? undefined : lastPage.number + 1),
    });

  if (isLoading) return <p className="text-sm text-muted">Loading timeline…</p>;
  if (isError) return <p className="text-sm text-danger">Couldn&apos;t load the timeline.</p>;

  const entries = data?.pages.flatMap((p) => p.content) ?? [];
  if (entries.length === 0)
    return <p className="text-sm text-muted">No activity yet.</p>;

  return (
    <div>
      <ol className="space-y-3">
        {entries.map((a) => {
          const Icon = ICONS[a.activityType] ?? Activity;
          return (
            <li key={a.id} className="flex gap-3 border-l border-hairline pl-4">
              <span className="-ml-[1.30rem] mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center border border-hairline bg-surface text-muted">
                <Icon size={13} aria-hidden />
              </span>
              <div>
                <p className="text-sm text-ink">{label(a)}</p>
                <p className="text-xs text-faint tabular">
                  {new Date(a.createdAt).toLocaleString()}
                </p>
              </div>
            </li>
          );
        })}
      </ol>
      {hasNextPage && (
        <Button
          size="sm"
          variant="ghost"
          className="mt-3"
          onClick={() => fetchNextPage()}
          disabled={isFetchingNextPage}
        >
          {isFetchingNextPage ? "Loading…" : "Load more"}
        </Button>
      )}
    </div>
  );
}
