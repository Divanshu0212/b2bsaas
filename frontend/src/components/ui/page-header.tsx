import type { ReactNode } from "react";

/**
 * Editorial page header: an eyebrow label over a serif title, with an action slot on the
 * right and a full-bleed hairline rule underneath — the recurring "statement heading" of the
 * Ledger design. `eyebrow` encodes what section of the app this is (kept short + uppercase).
 */
export function PageHeader({
  eyebrow,
  title,
  action,
}: {
  eyebrow: string;
  title: string;
  action?: ReactNode;
}) {
  return (
    <header className="mb-8 border-b border-hairline pb-4">
      <div className="flex items-end justify-between gap-4">
        <div>
          <p className="eyebrow mb-1.5">{eyebrow}</p>
          <h1 className="display text-3xl font-semibold tracking-tight text-ink">
            {title}
          </h1>
        </div>
        {action ? <div className="pb-1">{action}</div> : null}
      </div>
    </header>
  );
}
