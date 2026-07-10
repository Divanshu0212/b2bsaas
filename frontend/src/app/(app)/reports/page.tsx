"use client";

import { useQuery } from "@tanstack/react-query";
import { reportsApi } from "@/lib/api/endpoints";
import { money } from "@/lib/format";
import { PageHeader } from "@/components/ui/page-header";

/**
 * Reporting dashboard (T6.8): the pipeline funnel (deals per stage, in order) as horizontal
 * bars, plus a rep leaderboard by total value. Admin-only surface (nav hides it for reps).
 */
export default function ReportsPage() {
  const { data, isLoading, isError } = useQuery({
    queryKey: ["funnel"],
    queryFn: () => reportsApi.funnel(),
  });

  if (isLoading) return <p className="text-sm text-muted">Loading report…</p>;
  if (isError || !data)
    return <p className="text-sm text-danger">Couldn&apos;t load the report.</p>;

  const funnel = data.funnel.map((s) => ({
    name: s.stageName,
    count: s.dealCount,
    amount: s.totalAmount,
  }));
  const maxAmount = Math.max(1, ...funnel.map((f) => f.amount));
  const totalValue = funnel.reduce((sum, f) => sum + f.amount, 0);
  const totalDeals = funnel.reduce((sum, f) => sum + f.count, 0);

  return (
    <div className="mx-auto max-w-3xl">
      <PageHeader eyebrow="The statement" title="Reports" />

      {/* Pipeline funnel as a ledger: each stage a ruled line — name, a bar sized by value, the figure. */}
      <section className="mb-14">
        <div className="ledger-rule mb-6">
          <span className="eyebrow">Pipeline funnel</span>
          <span className="rule-fill" aria-hidden />
          <span className="tabular text-xs text-muted">
            {totalDeals} deals · <span className="display text-ink">{money(totalValue, null)}</span>
          </span>
        </div>

        {funnel.every((f) => f.count === 0) ? (
          <p className="py-10 text-center text-sm text-muted">No deals yet.</p>
        ) : (
          <ol className="space-y-6">
            {funnel.map((f) => (
              <li key={f.name}>
                <div className="mb-2 flex items-baseline justify-between gap-4">
                  <span className="text-sm font-medium text-ink">{f.name}</span>
                  <span className="tabular display text-lg text-ink">
                    {money(f.amount, null)}
                  </span>
                </div>
                {/* The bar reads left-to-right like a statement entry; width is share of the largest stage. */}
                <div className="flex items-center gap-3">
                  <div className="h-2 flex-1 bg-hairline/60">
                    <div
                      className="h-full bg-accent"
                      style={{ width: `${Math.max(2, (f.amount / maxAmount) * 100)}%` }}
                    />
                  </div>
                  <span className="tabular w-16 shrink-0 text-right text-xs text-muted">
                    {f.count} {f.count === 1 ? "deal" : "deals"}
                  </span>
                </div>
              </li>
            ))}
          </ol>
        )}
      </section>

      {/* Rep leaderboard — same ledger discipline: names left, figures right in serif. */}
      <section>
        <div className="ledger-rule mb-6">
          <span className="eyebrow">Rep leaderboard</span>
          <span className="rule-fill" aria-hidden />
        </div>
        {data.leaderboard.length === 0 ? (
          <p className="py-10 text-center text-sm text-muted">No owned deals yet.</p>
        ) : (
          <table className="w-full text-sm">
            <thead className="border-b border-hairline text-left">
              <tr className="[&>th]:eyebrow [&>th]:pb-2">
                <th>Rep</th>
                <th className="text-right">Deals</th>
                <th className="text-right">Value</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-hairline">
              {data.leaderboard.map((r) => (
                <tr key={r.ownerId}>
                  <td className="py-3 text-ink">{r.ownerEmail}</td>
                  <td className="tabular py-3 text-right text-muted">{r.dealCount}</td>
                  <td className="tabular display py-3 text-right text-base text-ink">
                    {money(r.totalAmount, null)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  );
}
