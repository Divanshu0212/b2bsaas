import { getAccessToken, setAccessToken, clearAccessToken } from "@/lib/auth/token-store";
import type { ProblemDetail } from "./schema";

/**
 * All API calls go through the app's own origin under /api/* — next.config rewrites that to
 * the backend, so the browser stays same-origin (the refresh cookie is sent, no CORS
 * preflight per request). Server-side (RSC/route handlers) there is no rewrite, so fall back
 * to the backend origin directly.
 */
const BASE = typeof window === "undefined"
  ? `${process.env.BACKEND_ORIGIN ?? "http://localhost:8080"}`
  : "/api";

/** Error carrying the parsed RFC 7807 problem so callers/toasts get a real message. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly problem: ProblemDetail | null,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export interface RequestOptions {
  method?: string;
  body?: unknown;
  /** Set false to skip the 401 refresh-retry (used by the refresh call itself). */
  retryOnUnauthorized?: boolean;
  signal?: AbortSignal;
}

let refreshInFlight: Promise<boolean> | null = null;

/** Exchange the httpOnly refresh cookie for a new access token. De-duped across callers. */
async function refresh(): Promise<boolean> {
  if (!refreshInFlight) {
    refreshInFlight = (async () => {
      try {
        const res = await fetch(`${BASE}/auth/refresh`, {
          method: "POST",
          credentials: "include",
        });
        if (!res.ok) return false;
        const data = (await res.json()) as { accessToken: string };
        setAccessToken(data.accessToken);
        return true;
      } catch {
        return false;
      } finally {
        refreshInFlight = null;
      }
    })();
  }
  return refreshInFlight;
}

async function parseProblem(res: Response): Promise<ProblemDetail | null> {
  const type = res.headers.get("content-type") ?? "";
  if (!type.includes("json")) return null;
  try {
    return (await res.json()) as ProblemDetail;
  } catch {
    return null;
  }
}

/**
 * Typed request. Attaches the in-memory access token; on a 401 it makes ONE silent
 * refresh-and-retry, and on refresh failure clears the token and signals the caller to
 * redirect to /login (via a thrown ApiError with status 401).
 */
export async function apiFetch<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const { method = "GET", body, retryOnUnauthorized = true, signal } = opts;

  const doFetch = async (): Promise<Response> => {
    const token = getAccessToken();
    const headers: Record<string, string> = {};
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (token) headers["Authorization"] = `Bearer ${token}`;
    return fetch(`${BASE}${path}`, {
      method,
      headers,
      credentials: "include",
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal,
    });
  };

  let res = await doFetch();

  if (res.status === 401 && retryOnUnauthorized) {
    const ok = await refresh();
    if (ok) {
      res = await doFetch();
    } else {
      clearAccessToken();
      // Refresh failed = session is gone. Bounce to login (browser only); the throw still
      // propagates so callers/queries stop.
      if (typeof window !== "undefined" && window.location.pathname !== "/login") {
        window.location.assign(`/login?next=${encodeURIComponent(window.location.pathname)}`);
      }
      throw new ApiError(401, await parseProblem(res), "Session expired");
    }
  }

  if (!res.ok) {
    const problem = await parseProblem(res);
    throw new ApiError(
      res.status,
      problem,
      problem?.detail ?? problem?.title ?? `Request failed (${res.status})`,
    );
  }

  // 204 / empty body
  if (res.status === 204 || res.headers.get("content-length") === "0") {
    return undefined as T;
  }
  return (await res.json()) as T;
}
