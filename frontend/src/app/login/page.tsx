"use client";

import { Suspense, useState } from "react";
import { useSearchParams } from "next/navigation";
import { authApi } from "@/lib/api/endpoints";
import { ApiError } from "@/lib/api/client";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

/**
 * Login + first-run register. On success the backend sets the httpOnly refresh cookie and
 * returns an access token (held in memory by authApi); we then navigate to the ?next target
 * or /pipeline. Register creates a new org (the caller becomes its ADMIN).
 */
export default function LoginPage() {
  // useSearchParams must sit under a Suspense boundary for static export.
  return (
    <Suspense>
      <LoginForm />
    </Suspense>
  );
}

function LoginForm() {
  const params = useSearchParams();
  const next = params.get("next") ?? "/pipeline";

  const [mode, setMode] = useState<"login" | "register">("login");
  const [orgName, setOrgName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      if (mode === "login") {
        await authApi.login({ email, password });
      } else {
        await authApi.register({ orgName, email, password });
      }
      // Full navigation so the proxy re-reads the freshly-set cookie.
      window.location.assign(next);
    } catch (err) {
      setError(
        err instanceof ApiError && err.status === 401
          ? "Wrong email or password."
          : "Couldn't sign in. Try again.",
      );
      setBusy(false);
    }
  }

  return (
    <main className="flex min-h-full items-center justify-center px-4">
      <div className="w-full max-w-sm">
        {/* Signature wordmark: the pipe glyph as a filled stage bar. */}
        <div className="mb-8 flex items-center gap-2">
          <span className="inline-block h-5 w-1.5 rounded-full bg-accent" aria-hidden />
          <span className="text-lg font-semibold tracking-tight">SalesPipe</span>
        </div>

        <h1 className="mb-1 text-2xl font-semibold tracking-tight">
          {mode === "login" ? "Sign in" : "Create your workspace"}
        </h1>
        <p className="mb-6 text-sm text-muted">
          {mode === "login"
            ? "Access your pipeline."
            : "Start a new org — you'll be its admin."}
        </p>

        <form onSubmit={onSubmit} className="space-y-3">
          {mode === "register" && (
            <div>
              <label htmlFor="org" className="mb-1 block text-sm text-muted">
                Organization
              </label>
              <Input
                id="org"
                value={orgName}
                onChange={(e) => setOrgName(e.target.value)}
                required
                autoComplete="organization"
              />
            </div>
          )}
          <div>
            <label htmlFor="email" className="mb-1 block text-sm text-muted">
              Email
            </label>
            <Input
              id="email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
              autoComplete="email"
            />
          </div>
          <div>
            <label htmlFor="password" className="mb-1 block text-sm text-muted">
              Password
            </label>
            <Input
              id="password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              autoComplete={mode === "login" ? "current-password" : "new-password"}
            />
          </div>

          {error && (
            <p className="text-sm text-danger" role="alert">
              {error}
            </p>
          )}

          <Button type="submit" className="w-full" disabled={busy}>
            {busy ? "…" : mode === "login" ? "Sign in" : "Create workspace"}
          </Button>
        </form>

        <button
          type="button"
          className="mt-4 text-sm text-accent hover:underline"
          onClick={() => {
            setMode((m) => (m === "login" ? "register" : "login"));
            setError(null);
          }}
        >
          {mode === "login" ? "Create a new workspace" : "Have an account? Sign in"}
        </button>
      </div>
    </main>
  );
}
