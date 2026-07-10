"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Check, ChevronDown, X } from "lucide-react";
import { leadsApi } from "@/lib/api/endpoints";
import { cn } from "@/lib/cn";
import type { LeadResponse } from "@/lib/api/schema";

/**
 * Searchable lead picker. Leads have no display name, so each row is labelled by
 * "status · source · short-id" — enough to recognise one, with the short id to disambiguate
 * look-alikes. Emits the selected lead's id (or "" when cleared) via `onChange`, so it drops
 * into a react-hook-form field holding an optional UUID.
 *
 * Same interaction + editorial styling as AccountCombobox: type-to-filter, ↑/↓/Enter/Esc,
 * clearable, click-away close.
 */
function labelOf(lead: LeadResponse): string {
  const parts = [lead.status.toLowerCase()];
  if (lead.source) parts.push(lead.source);
  parts.push(lead.id.slice(0, 8));
  return parts.join(" · ");
}

export function LeadCombobox({
  id,
  value,
  onChange,
  placeholder = "Search leads…",
}: {
  id?: string;
  /** Selected lead id, or "" for none. */
  value: string;
  onChange: (leadId: string) => void;
  placeholder?: string;
}) {
  const { data } = useQuery({
    queryKey: ["leads", "all", 0, 200],
    queryFn: () => leadsApi.list({ page: 0, size: 200 }),
  });
  const leads = useMemo(() => data?.content ?? [], [data]);

  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);
  const rootRef = useRef<HTMLDivElement>(null);

  const selected = leads.find((l) => l.id === value) ?? null;

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return leads;
    return leads.filter((l) => labelOf(l).toLowerCase().includes(q));
  }, [leads, query]);

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

  function choose(leadId: string) {
    onChange(leadId);
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
          value={open ? query : selected ? labelOf(selected) : ""}
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
            aria-label="Clear lead"
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
            <li className="px-3 py-3 text-sm text-muted">No leads match.</li>
          ) : (
            filtered.map((l, i) => {
              const isSelected = l.id === value;
              const isActive = i === Math.min(active, filtered.length - 1);
              return (
                <li key={l.id} role="option" aria-selected={isSelected}>
                  <button
                    type="button"
                    className={cn(
                      "flex w-full items-center justify-between gap-2 px-3 py-2.5 text-left text-sm transition-colors",
                      isActive ? "bg-accent-soft text-ink" : "text-ink hover:bg-accent-soft/50",
                    )}
                    onMouseEnter={() => setActive(i)}
                    onClick={() => choose(l.id)}
                  >
                    <span className="truncate capitalize">{labelOf(l)}</span>
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
