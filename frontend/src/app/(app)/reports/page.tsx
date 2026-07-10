"use client";

import { useQuery } from "@tanstack/react-query";
import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  ResponsiveContainer,
  Tooltip,
  Cell,
} from "recharts";
import { reportsApi } from "@/lib/api/endpoints";
import { money } from "@/lib/format";

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
  const maxCount = Math.max(1, ...funnel.map((f) => f.count));

  return (
    <div className="mx-auto max-w-4xl">
      <h1 className="mb-6 text-xl font-semibold tracking-tight">Reports</h1>

      <section className="mb-8 rounded-lg border border-hairline bg-surface p-4">
        <h2 className="mb-4 text-sm font-semibold uppercase tracking-wide text-muted">
          Pipeline funnel
        </h2>
        {funnel.every((f) => f.count === 0) ? (
          <p className="py-6 text-center text-sm text-muted">No deals yet.</p>
        ) : (
          <div style={{ height: funnel.length * 48 + 24 }}>
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={funnel} layout="vertical" margin={{ left: 8, right: 24 }}>
                <XAxis type="number" hide domain={[0, maxCount]} />
                <YAxis
                  type="category"
                  dataKey="name"
                  width={120}
                  tick={{ fontSize: 12, fill: "var(--muted)" }}
                  axisLine={false}
                  tickLine={false}
                />
                <Tooltip
                  cursor={{ fill: "var(--hairline)", opacity: 0.4 }}
                  formatter={(v, _n, item) => {
                    const amt = (item?.payload as { amount: number } | undefined)?.amount ?? 0;
                    return [`${v} deals · ${money(amt, null)}`, ""];
                  }}
                />
                <Bar dataKey="count" radius={[0, 4, 4, 0]}>
                  {funnel.map((_, i) => (
                    // Progressively cooler down the funnel via the accent.
                    <Cell key={i} fill="var(--accent)" fillOpacity={1 - i * 0.12} />
                  ))}
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          </div>
        )}
      </section>

      <section className="rounded-lg border border-hairline bg-surface p-4">
        <h2 className="mb-4 text-sm font-semibold uppercase tracking-wide text-muted">
          Rep leaderboard
        </h2>
        {data.leaderboard.length === 0 ? (
          <p className="py-6 text-center text-sm text-muted">No owned deals yet.</p>
        ) : (
          <table className="w-full text-sm">
            <thead className="border-b border-hairline text-left text-xs uppercase tracking-wide text-muted">
              <tr>
                <th className="py-2 font-medium">Rep</th>
                <th className="py-2 text-right font-medium">Deals</th>
                <th className="py-2 text-right font-medium">Value</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-hairline">
              {data.leaderboard.map((r) => (
                <tr key={r.ownerId}>
                  <td className="py-2.5 text-ink">{r.ownerEmail}</td>
                  <td className="tabular py-2.5 text-right text-muted">{r.dealCount}</td>
                  <td className="tabular py-2.5 text-right text-ink">
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
