"use client";

import { useState } from "react";
import { LogOut, ChevronDown } from "lucide-react";
import { authApi } from "@/lib/api/endpoints";
import { NotificationBell } from "@/components/notifications/NotificationBell";
import type { Session } from "@/lib/auth/session";

export function Topbar({ session }: { session: Session | null }) {
  const [menuOpen, setMenuOpen] = useState(false);

  async function onLogout() {
    try {
      await authApi.logout();
    } finally {
      window.location.assign("/login");
    }
  }

  return (
    <header className="flex h-14 shrink-0 items-center justify-between border-b border-hairline bg-surface px-6">
      <div className="text-sm text-muted">
        {session ? (
          <span className="tabular">
            Role: <span className="text-ink">{session.role}</span>
          </span>
        ) : null}
      </div>

      <div className="flex items-center gap-3">
        <NotificationBell />
        <div className="relative">
          <button
            className="flex items-center gap-1 rounded-md px-2 py-1 text-sm text-muted hover:bg-hairline/50"
            onClick={() => setMenuOpen((o) => !o)}
            aria-haspopup="menu"
            aria-expanded={menuOpen}
          >
            Account
            <ChevronDown size={16} aria-hidden />
          </button>
          {menuOpen && (
            <div
              className="absolute right-0 mt-1 w-40 rounded-md border border-hairline bg-surface p-1 shadow-lg"
              role="menu"
            >
              <button
                className="flex w-full items-center gap-2 rounded px-3 py-2 text-sm text-ink hover:bg-hairline/60"
                onClick={onLogout}
                role="menuitem"
              >
                <LogOut size={16} aria-hidden />
                Sign out
              </button>
            </div>
          )}
        </div>
      </div>
    </header>
  );
}
