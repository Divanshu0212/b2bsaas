"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { LineChart, Line, YAxis, ResponsiveContainer, Tooltip } from "recharts";
import { leadsApi } from "@/lib/api/endpoints";
import { scoreBand } from "@/lib/format";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/toast";
import type { ScoreResponse } from "@/lib/api/schema";

/**
 * Lead score panel (T6.6): the current score with its band color, a sparkline of history,
 * and the top SHAP factors as a signed horizontal bar list — the "why is this lead hot"
 * answer. Manual refresh drives the sync recompute path (Phase 3 T3.4) with a local busy
 * state, never a page-blocking spinner.
 */
export function ScorePanel({ leadId }: { leadId: string }) {
  const qc = useQueryClient();
  const { toast } = useToast();

  const { data, isLoading, isError } = useQuery({
    queryKey: ["score", leadId],
    queryFn: () => leadsApi.score(leadId),
  });

  const refresh = useMutation({
    mutationFn: () => leadsApi.refreshScore(leadId),
    onSuccess: (fresh: ScoreResponse) => {
      qc.setQueryData(["score", leadId], fresh);
      toast("Score refreshed.", "success");
    },
    onError: () => toast("Couldn't refresh the score.", "error"),
  });

  if (isLoading) return <PanelSkeleton />;
  if (isError || !data)
    return (
      <section className="rounded-lg border border-hairline bg-surface p-4">
        <p className="text-sm text-danger">Couldn&apos;t load the score.</p>
      </section>
    );

  const latest = data.latest;
  const scoreNum = latest ? Number(latest.score) : null;
  const band = scoreBand(scoreNum);
  // Oldest → newest for the sparkline (history comes newest-first).
  const series = [...data.history]
    .reverse()
    .map((p, i) => ({ i, score: Number(p.score) }));
  const maxImpact = Math.max(1e-6, ...(latest?.topFactors ?? []).map((f) => Math.abs(f.impact)));

  return (
    <section className="rounded-lg border border-hairline bg-surface p-4">
      <div className="flex items-start justify-between">
        <div>
          <p className="text-xs uppercase tracking-wide text-muted">Lead score</p>
          <div className="mt-1 flex items-baseline gap-2">
            <span className="tabular text-3xl font-semibold" style={{ color: band.color }}>
              {scoreNum != null ? Math.round(scoreNum * 100) : "—"}
            </span>
            <span className="text-sm font-medium" style={{ color: band.color }}>
              {band.label}
            </span>
          </div>
        </div>
        <Button
          size="sm"
          variant="outline"
          onClick={() => refresh.mutate()}
          disabled={refresh.isPending}
        >
          {refresh.isPending ? "Refreshing…" : "Refresh score"}
        </Button>
      </div>

      {series.length > 1 && (
        <div className="mt-3 h-16">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={series}>
              <YAxis domain={[0, 1]} hide />
              <Tooltip
                formatter={(v) => [`${Math.round(Number(v) * 100)}`, "score"]}
                labelFormatter={() => ""}
              />
              <Line
                type="monotone"
                dataKey="score"
                stroke="var(--accent)"
                strokeWidth={2}
                dot={false}
              />
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}

      {latest && latest.topFactors.length > 0 && (
        <div className="mt-4">
          <p className="mb-2 text-xs uppercase tracking-wide text-muted">Top factors</p>
          <ul className="space-y-1.5">
            {latest.topFactors.map((f) => {
              const pct = (Math.abs(f.impact) / maxImpact) * 100;
              const positive = f.impact >= 0;
              return (
                <li key={f.feature} className="flex items-center gap-2 text-sm">
                  <span className="w-40 shrink-0 truncate text-muted" title={f.feature}>
                    {f.feature.replaceAll("_", " ")}
                  </span>
                  <span className="flex h-2 flex-1 overflow-hidden rounded bg-hairline/60">
                    <span
                      className="h-full rounded"
                      style={{
                        width: `${pct}%`,
                        background: positive ? "var(--score-hot)" : "var(--score-cold)",
                      }}
                    />
                  </span>
                  <span className="tabular w-14 shrink-0 text-right text-xs text-faint">
                    {f.impact >= 0 ? "+" : ""}
                    {f.impact.toFixed(2)}
                  </span>
                </li>
              );
            })}
          </ul>
        </div>
      )}
    </section>
  );
}

function PanelSkeleton() {
  return (
    <section className="rounded-lg border border-hairline bg-surface p-4">
      <div className="h-8 w-20 rounded bg-hairline" />
      <div className="mt-3 h-16 rounded bg-hairline/50" />
    </section>
  );
}
