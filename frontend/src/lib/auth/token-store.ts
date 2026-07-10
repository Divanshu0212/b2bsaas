/*
 * Access token held in a module variable — in MEMORY ONLY, never localStorage/sessionStorage
 * (T6.3: those are readable by any injected script, an XSS token-exfiltration surface). The
 * long-lived refresh token lives in an httpOnly cookie the JS here can't read at all. On a
 * full page reload the access token is gone; the app silently re-mints one via /auth/refresh
 * using the cookie.
 */
let accessToken: string | null = null;

export function getAccessToken(): string | null {
  return accessToken;
}

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

export function clearAccessToken(): void {
  accessToken = null;
}
