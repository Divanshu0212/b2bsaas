"use client";

import { useEffect, useState, type ReactNode } from "react";
import { Sidebar } from "./Sidebar";
import { Topbar } from "./Topbar";
import { getSession, type Session } from "@/lib/auth/session";
import { getAccessToken, setAccessToken } from "@/lib/auth/token-store";

/**
 * App chrome: left rail + top bar around the page. On mount, if there's no in-memory access
 * token (e.g. a fresh page load — the token only lived in memory), it silently mints one from
 * the refresh cookie so the session/role is known for RBAC nav. If refresh fails, the proxy
 * has already gated the route; the page's own data fetch will surface the 401.
 */
export function AppShell({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(() => getSession());
  const [ready, setReady] = useState<boolean>(() => getAccessToken() !== null);

  useEffect(() => {
    if (getAccessToken()) {
      setSession(getSession());
      setReady(true);
      return;
    }
    // No token in memory (page reload): re-mint from the httpOnly refresh cookie.
    let cancelled = false;
    (async () => {
      try {
        const res = await fetch("/api/auth/refresh", {
          method: "POST",
          credentials: "include",
        });
        if (res.ok) {
          const data = (await res.json()) as { accessToken: string };
          setAccessToken(data.accessToken);
        }
      } catch {
        // ignore — route is already gated by proxy
      } finally {
        if (!cancelled) {
          setSession(getSession());
          setReady(true);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <div className="flex min-h-full flex-1">
      <Sidebar role={session?.role ?? null} />
      <div className="flex min-w-0 flex-1 flex-col">
        <Topbar session={session} />
        <main className="min-w-0 flex-1 overflow-auto p-6">
          {ready ? children : <div className="text-sm text-muted">Loading…</div>}
        </main>
      </div>
    </div>
  );
}
