/** Format a money amount + ISO currency; falls back gracefully when either is missing. */
export function money(amount: number | null, currency: string | null): string {
  if (amount == null) return "—";
  try {
    return new Intl.NumberFormat(undefined, {
      style: "currency",
      currency: currency ?? "USD",
      maximumFractionDigits: 0,
    }).format(amount);
  } catch {
    return `${amount.toLocaleString()} ${currency ?? ""}`.trim();
  }
}

/**
 * Score band + color for a 0..1 lead score, using the cold→hot ramp tokens. The threshold
 * mirrors the backend hot-lead cutoff (0.75 default) so "hot" here means the same as the
 * notification the rep already got.
 */
export function scoreBand(score: number | null): { label: string; color: string } {
  if (score == null) return { label: "—", color: "var(--faint)" };
  if (score >= 0.75) return { label: "Hot", color: "var(--score-hot)" };
  if (score >= 0.5) return { label: "Warm", color: "var(--score-warm)" };
  return { label: "Cold", color: "var(--score-cold)" };
}
