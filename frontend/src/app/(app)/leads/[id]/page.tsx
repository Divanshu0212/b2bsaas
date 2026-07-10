"use client";

import { use } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { leadsApi } from "@/lib/api/endpoints";
import { ScorePanel } from "@/components/leads/ScorePanel";
import { TimelineFeed } from "@/components/leads/TimelineFeed";
import { Button } from "@/components/ui/button";

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
      <Link
        href="/leads"
        className="eyebrow text-accent transition-colors hover:text-ink"
      >
        ← Leads
      </Link>

      <header className="mb-8 mt-3 flex items-end justify-between gap-4 border-b border-hairline pb-4">
        <div>
          <p className="eyebrow mb-1.5">
            {lead ? (
              <>
                <span className="capitalize">{lead.status.toLowerCase()}</span>
                {lead.source ? ` · ${lead.source}` : ""}
              </>
            ) : (
              "Lead"
            )}
          </p>
          <h1 className="display text-3xl font-semibold tracking-tight text-ink">
            {isLoading ? "Lead" : `Lead ${id.slice(0, 8)}`}
          </h1>
        </div>
        <Link href={`/leads/${id}/edit`} className="pb-1">
          <Button size="sm" variant="outline">
            Edit
          </Button>
        </Link>
      </header>

      <div className="grid gap-8 md:grid-cols-[1fr_1.2fr]">
        <ScorePanel leadId={id} />

        <section>
          <div className="ledger-rule mb-4">
            <span className="eyebrow">Timeline</span>
            <span className="rule-fill" aria-hidden />
          </div>
          <TimelineFeed leadId={id} />
        </section>
      </div>
    </div>
  );
}
