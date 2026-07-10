"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Check, ChevronDown, X } from "lucide-react";
import { accountsApi } from "@/lib/api/endpoints";
import { cn } from "@/lib/cn";

/**
 * Searchable account picker. Fetches the account list once (first 200 — enough for the common
 * org) and filters by name as the user types. Emits the selected account's id (or "" when
 * cleared) via `onChange`, so it drops straight into a react-hook-form field that stores an
 * optional UUID. Keyboard: ↑/↓ move, Enter selects, Esc closes; click-away closes.
 *
 * Styled to match the editorial underline inputs — no chrome until focused, hairline rule,
 * accent on active. The trigger shows the chosen account's NAME while the form holds its ID.
 */
export function AccountCombobox({
  id,
  value,
  onChange,
  placeholder = "Search accounts…",
}: {
  id?: string;
  /** Selected account id, or "" for none. */
  value: string;
  onChange: (accountId: string) => void;
  placeholder?: string;
}) {
  const { data } = useQuery({
    queryKey: ["accounts", 0, 200],
    queryFn: () => accountsApi.list(0, 200),
  });
  const accounts = useMemo(() => data?.content ?? [], [data]);

  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0); // highlighted option index
  const rootRef = useRef<HTMLDivElement>(null);

  const selected = accounts.find((a) => a.id === value) ?? null;

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return accounts;
    return accounts.filter((a) => a.name.toLowerCase().includes(q));
  }, [accounts, query]);

  // Close on outside click.
  useEffect(() => {
    if (!open) return;
    function onDocClick(e: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) {
        setOpen(false);
        setQuery("");
      }
    }
    document.addEventListener("mousedown", onDocClick);
    return () => document.removeEventListener("mousedown", onDocClick);
  }, [open]);

  function choose(accountId: string) {
    onChange(accountId);
    setOpen(false);
    setQuery("");
  }

  function onKeyDown(e: React.KeyboardEvent) {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      if (!open) setOpen(true);
      setActive((i) => Math.min(i + 1, filtered.length - 1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setActive((i) => Math.max(i - 1, 0));
    } else if (e.key === "Enter") {
      const pick = filtered[Math.min(active, filtered.length - 1)];
      if (open && pick) {
        e.preventDefault();
        choose(pick.id);
      }
    } else if (e.key === "Escape") {
      setOpen(false);
      setQuery("");
    }
  }

  return (
    <div ref={rootRef} className="relative">
      <div className="flex items-center border-b border-hairline transition-colors focus-within:border-accent">
        <input
          id={id}
          type="text"
          role="combobox"
          aria-expanded={open}
          aria-controls={id ? `${id}-listbox` : undefined}
          aria-autocomplete="list"
          autoComplete="off"
          className="h-10 w-full bg-transparent px-0.5 text-sm text-ink placeholder:text-faint focus-visible:outline-none"
          // Show the query while typing; otherwise show the chosen account's name.
          value={open ? query : selected?.name ?? ""}
          placeholder={placeholder}
          onChange={(e) => {
            setQuery(e.target.value);
            setActive(0);
            if (!open) setOpen(true);
          }}
          onFocus={() => {
            setActive(0);
            setOpen(true);
          }}
          onKeyDown={onKeyDown}
        />
        {value ? (
          <button
            type="button"
            aria-label="Clear account"
            className="p-1 text-faint transition-colors hover:text-danger"
            onClick={() => choose("")}
          >
            <X size={15} aria-hidden />
          </button>
        ) : (
          <ChevronDown size={15} className="mr-1 text-faint" aria-hidden />
        )}
      </div>

      {open && (
        <ul
          id={id ? `${id}-listbox` : undefined}
          role="listbox"
          className="absolute z-20 mt-1 max-h-64 w-full overflow-auto border border-hairline bg-surface shadow-[0_8px_24px_-12px_rgba(15,23,42,0.3)]"
        >
          {filtered.length === 0 ? (
            <li className="px-3 py-3 text-sm text-muted">No accounts match.</li>
          ) : (
            filtered.map((a, i) => {
              const isSelected = a.id === value;
              // Clamp so a stale highlight (list shrank on the last keystroke) still lands on a row.
              const isActive = i === Math.min(active, filtered.length - 1);
              return (
                <li key={a.id} role="option" aria-selected={isSelected}>
                  <button
                    type="button"
                    className={cn(
                      "flex w-full items-center justify-between gap-2 px-3 py-2.5 text-left text-sm transition-colors",
                      isActive ? "bg-accent-soft text-ink" : "text-ink hover:bg-accent-soft/50",
                    )}
                    // Highlight follows the pointer so mouse + keyboard agree.
                    onMouseEnter={() => setActive(i)}
                    onClick={() => choose(a.id)}
                  >
                    <span className="truncate">{a.name}</span>
                    {isSelected && <Check size={15} className="shrink-0 text-accent" aria-hidden />}
                  </button>
                </li>
              );
            })
          )}
        </ul>
      )}
    </div>
  );
}
