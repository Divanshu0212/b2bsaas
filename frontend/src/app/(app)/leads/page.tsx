"use client";

import { useState } from "react";
import Link from "next/link";
import { useQuery, keepPreviousData } from "@tanstack/react-query";
import { leadsApi } from "@/lib/api/endpoints";
import { Button } from "@/components/ui/button";
import { PageHeader } from "@/components/ui/page-header";
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
      <PageHeader
        eyebrow="Inbound & scored"
        title="Leads"
        action={
          <Link href="/leads/new">
            <Button size="sm">New lead</Button>
          </Link>
        }
      />

      {/* Status filter */}
      <div className="mb-6 flex flex-wrap gap-1.5">
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
        <p className="border-t border-hairline py-16 text-center text-sm text-muted">
          No leads match this filter.
        </p>
      ) : (
        <>
          <div className="border-t border-hairline">
            <table className="w-full text-sm">
              <thead className="border-b border-hairline text-left">
                <tr className="[&>th]:eyebrow [&>th]:px-1 [&>th]:pb-2 [&>th]:pt-0">
                  <th>Status</th>
                  <th>Source</th>
                  <th>Notes</th>
                  <th />
                </tr>
              </thead>
              <tbody className="divide-y divide-hairline">
                {data.content.map((lead) => (
                  <tr key={lead.id} className="group transition-colors hover:bg-accent-soft/40">
                    <td className="px-1 py-3">
                      <span
                        className="inline-flex items-center gap-2 text-xs font-semibold uppercase tracking-wide"
                        style={{ color: STATUS_COLORS[lead.status] }}
                      >
                        <span
                          className="h-1.5 w-1.5"
                          style={{ background: STATUS_COLORS[lead.status] }}
                        />
                        {lead.status.toLowerCase()}
                      </span>
                    </td>
                    <td className="px-1 py-3 text-muted">{lead.source ?? "—"}</td>
                    <td className="max-w-xs truncate px-1 py-3 text-muted">
                      {lead.rawNotes ?? "—"}
                    </td>
                    <td className="px-1 py-3 text-right">
                      <Link
                        href={`/leads/${lead.id}`}
                        className="text-accent underline-offset-4 hover:underline"
                      >
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
        "border px-3 py-1 text-xs font-medium capitalize tracking-wide transition-colors",
        active
          ? "border-accent bg-accent text-accent-fg"
          : "border-hairline text-muted hover:border-ink hover:text-ink",
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
