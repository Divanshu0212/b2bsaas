"use client";

import { useEffect } from "react";
import { ApiError } from "@/lib/api/client";
import { Button } from "@/components/ui/button";

/**
 * App-level error boundary (T6.10). A 401 that reaches here means refresh already failed —
 * send the user to /login. Everything else renders a recoverable "can't reach server" state
 * with a retry, never a blank page.
 */
export default function AppError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    if (error instanceof ApiError && error.status === 401) {
      window.location.assign("/login");
    }
  }, [error]);

  const message =
    error instanceof ApiError ? error.message : "We couldn't reach the server.";

  return (
    <div className="mx-auto max-w-md py-16 text-center">
      <h1 className="text-lg font-semibold">Something went wrong</h1>
      <p className="mt-2 text-sm text-muted">{message}</p>
      <Button className="mt-4" onClick={() => reset()}>
        Try again
      </Button>
    </div>
  );
}
