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
      <div>
        {session ? (
          <span className="eyebrow">
            {session.role.replace("_", " ")}
          </span>
        ) : null}
      </div>

      <div className="flex items-center gap-4">
        <NotificationBell />
        <div className="relative">
          <button
            className="flex items-center gap-1.5 text-[13px] font-medium text-muted transition-colors hover:text-ink"
            onClick={() => setMenuOpen((o) => !o)}
            aria-haspopup="menu"
            aria-expanded={menuOpen}
          >
            Account
            <ChevronDown size={15} aria-hidden />
          </button>
          {menuOpen && (
            <div
              className="absolute right-0 mt-2 w-44 border border-hairline bg-surface p-1"
              role="menu"
            >
              <button
                className="flex w-full items-center gap-2 px-3 py-2 text-[13px] text-ink transition-colors hover:bg-accent-soft"
                onClick={onLogout}
                role="menuitem"
              >
                <LogOut size={15} aria-hidden />
                Sign out
              </button>
            </div>
          )}
        </div>
      </div>
    </header>
  );
}
