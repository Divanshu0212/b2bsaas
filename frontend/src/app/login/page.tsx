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
    <main className="flex min-h-full">
      {/* Editorial split: a serif thesis on the left, the quiet form on the right. */}
      <aside className="relative hidden w-1/2 flex-col justify-between border-r border-hairline bg-surface p-12 lg:flex">
        <div className="flex items-baseline gap-2">
          <span className="display text-xl font-semibold tracking-tight text-ink">
            SalesPipe
          </span>
          <span className="inline-block h-1.5 w-1.5 translate-y-[-0.15em] bg-accent" aria-hidden />
        </div>

        <div className="max-w-md">
          <p className="eyebrow mb-4">The pipeline, kept like a ledger</p>
          <p className="display text-4xl leading-[1.1] tracking-tight text-ink">
            Every stage, score, and figure — set in a single column you can read
            top to bottom.
          </p>
        </div>

        {/* A quiet ledger rule as ambient signature. */}
        <div className="space-y-3 text-[13px] text-muted">
          <div className="ledger-rule">
            <span>Qualified</span>
            <span className="rule-fill" aria-hidden />
            <span className="tabular display text-ink">$842,000</span>
          </div>
          <div className="ledger-rule">
            <span>Proposal</span>
            <span className="rule-fill" aria-hidden />
            <span className="tabular display text-ink">$310,000</span>
          </div>
        </div>
      </aside>

      <div className="flex w-full flex-col items-center justify-center px-6 lg:w-1/2">
        <div className="w-full max-w-sm">
          {/* Wordmark repeats on narrow screens where the aside is hidden. */}
          <div className="mb-10 flex items-baseline gap-2 lg:hidden">
            <span className="display text-xl font-semibold tracking-tight text-ink">
              SalesPipe
            </span>
            <span className="inline-block h-1.5 w-1.5 translate-y-[-0.15em] bg-accent" aria-hidden />
          </div>

          <p className="eyebrow mb-3">{mode === "login" ? "Welcome back" : "Get started"}</p>
          <h1 className="display mb-2 text-3xl font-semibold tracking-tight text-ink">
            {mode === "login" ? "Sign in" : "Create your workspace"}
          </h1>
          <p className="mb-8 text-sm text-muted">
            {mode === "login"
              ? "Access your pipeline."
              : "Start a new org — you'll be its admin."}
          </p>

          <form onSubmit={onSubmit} className="space-y-5">
          {mode === "register" && (
            <div>
              <label htmlFor="org" className="eyebrow mb-2 block">
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
            <label htmlFor="email" className="eyebrow mb-2 block">
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
            <label htmlFor="password" className="eyebrow mb-2 block">
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
            className="mt-6 text-[13px] font-medium text-accent underline-offset-4 hover:underline"
            onClick={() => {
              setMode((m) => (m === "login" ? "register" : "login"));
              setError(null);
            }}
          >
            {mode === "login" ? "Create a new workspace" : "Have an account? Sign in"}
          </button>
        </div>
      </div>
    </main>
  );
}
