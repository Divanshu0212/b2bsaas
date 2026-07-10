"use client";

import { useState } from "react";
import Link from "next/link";
import { useQuery, keepPreviousData } from "@tanstack/react-query";
import { leadsApi } from "@/lib/api/endpoints";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/cn";
import type { LeadStatus } from "@/lib/api/schema";

const STATUSES: LeadStatus[] = [
  "NEW",
  "CONTACTED",
  "QUALIFIED",
  "UNQUALIFIED",
  "CONVERTED",
];

const STATUS_COLORS: Record<LeadStatus, string> = {
  NEW: "var(--accent)",
  CONTACTED: "var(--score-warm)",
  QUALIFIED: "var(--won)",
  UNQUALIFIED: "var(--faint)",
  CONVERTED: "var(--won)",
};

export default function LeadsPage() {
  const [status, setStatus] = useState<LeadStatus | undefined>(undefined);
  const [page, setPage] = useState(0);

  const { data, isLoading, isError } = useQuery({
    queryKey: ["leads", status, page],
    queryFn: () => leadsApi.list({ status, page, size: 20 }),
    placeholderData: keepPreviousData,
  });

  return (
    <div>
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-xl font-semibold tracking-tight">Leads</h1>
        <Link href="/leads/new">
          <Button size="sm">New lead</Button>
        </Link>
      </div>

      {/* Status filter */}
      <div className="mb-4 flex flex-wrap gap-1.5">
        <FilterChip active={status === undefined} onClick={() => { setStatus(undefined); setPage(0); }}>
          All
        </FilterChip>
        {STATUSES.map((s) => (
          <FilterChip
            key={s}
            active={status === s}
            onClick={() => { setStatus(s); setPage(0); }}
          >
            {s.toLowerCase()}
          </FilterChip>
        ))}
      </div>

      {isLoading ? (
        <TableSkeleton />
      ) : isError || !data ? (
        <p className="text-sm text-danger">Couldn&apos;t load leads.</p>
      ) : data.content.length === 0 ? (
        <p className="rounded-md border border-hairline bg-surface p-8 text-center text-sm text-muted">
          No leads match this filter.
        </p>
      ) : (
        <>
          <div className="overflow-hidden rounded-md border border-hairline bg-surface">
            <table className="w-full text-sm">
              <thead className="border-b border-hairline text-left text-xs uppercase tracking-wide text-muted">
                <tr>
                  <th className="px-4 py-2 font-medium">Status</th>
                  <th className="px-4 py-2 font-medium">Source</th>
                  <th className="px-4 py-2 font-medium">Notes</th>
                  <th className="px-4 py-2" />
                </tr>
              </thead>
              <tbody className="divide-y divide-hairline">
                {data.content.map((lead) => (
                  <tr key={lead.id} className="hover:bg-hairline/30">
                    <td className="px-4 py-2.5">
                      <span
                        className="inline-flex items-center gap-1.5 text-xs font-medium"
                        style={{ color: STATUS_COLORS[lead.status] }}
                      >
                        <span
                          className="h-1.5 w-1.5 rounded-full"
                          style={{ background: STATUS_COLORS[lead.status] }}
                        />
                        {lead.status.toLowerCase()}
                      </span>
                    </td>
                    <td className="px-4 py-2.5 text-muted">{lead.source ?? "—"}</td>
                    <td className="max-w-xs truncate px-4 py-2.5 text-muted">
                      {lead.rawNotes ?? "—"}
                    </td>
                    <td className="px-4 py-2.5 text-right">
                      <Link href={`/leads/${lead.id}`} className="text-accent hover:underline">
                        Open
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="mt-3 flex items-center justify-between text-sm text-muted">
            <span className="tabular">
              Page {data.number + 1} of {Math.max(1, data.totalPages)} · {data.totalElements} total
            </span>
            <div className="flex gap-2">
              <Button
                size="sm"
                variant="outline"
                disabled={data.first}
                onClick={() => setPage((p) => Math.max(0, p - 1))}
              >
                Previous
              </Button>
              <Button
                size="sm"
                variant="outline"
                disabled={data.last}
                onClick={() => setPage((p) => p + 1)}
              >
                Next
              </Button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

function FilterChip({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      onClick={onClick}
      className={cn(
        "rounded-full border px-3 py-1 text-xs capitalize transition-colors",
        active
          ? "border-accent bg-accent-soft text-accent"
          : "border-hairline text-muted hover:bg-hairline/40",
      )}
    >
      {children}
    </button>
  );
}

function TableSkeleton() {
  return (
    <div className="space-y-2">
      {[0, 1, 2, 3, 4].map((i) => (
        <div key={i} className="h-11 rounded bg-hairline/50" />
      ))}
    </div>
  );
}
