"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";

/**
 * TanStack Query provider. One client per browser session (created in state so it isn't
 * recreated on re-render). Sensible defaults: don't refetch on window focus for a CRM
 * (avoids surprise reloads mid-edit), retry once.
 */
export function QueryProvider({ children }: { children: ReactNode }) {
  const [client] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: { refetchOnWindowFocus: false, retry: 1, staleTime: 10_000 },
        },
      }),
  );
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}
