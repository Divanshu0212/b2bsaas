"use client";

import { use } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { leadsApi } from "@/lib/api/endpoints";
import { ScorePanel } from "@/components/leads/ScorePanel";
import { TimelineFeed } from "@/components/leads/TimelineFeed";

/** Lead detail: header (status/source), score+SHAP panel, and the activity timeline. */
export default function LeadDetailPage({ params }: { params: Promise<{ id: string }> }) {
  // Next 16: route params are a Promise, unwrapped with React.use().
  const { id } = use(params);

  const { data: lead, isLoading } = useQuery({
    queryKey: ["lead", id],
    queryFn: () => leadsApi.get(id),
  });

  return (
    <div className="mx-auto max-w-4xl">
      <Link href="/leads" className="text-sm text-accent hover:underline">
        ← Leads
      </Link>

      <header className="mt-2 mb-6">
        <h1 className="text-xl font-semibold tracking-tight">
          {isLoading ? "Lead" : `Lead ${id.slice(0, 8)}`}
        </h1>
        {lead && (
          <p className="mt-1 text-sm text-muted">
            <span className="capitalize">{lead.status.toLowerCase()}</span>
            {lead.source ? ` · ${lead.source}` : ""}
          </p>
        )}
      </header>

      <div className="grid gap-6 md:grid-cols-[1fr_1.2fr]">
        <ScorePanel leadId={id} />

        <section>
          <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-muted">
            Timeline
          </h2>
          <TimelineFeed leadId={id} />
        </section>
      </div>
    </div>
  );
}
