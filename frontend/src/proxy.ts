import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

/*
 * T6.3 route protection. In Next 16 middleware is `proxy.ts`. This is an OPTIMISTIC check
 * only (the docs are explicit: proxy is not a session/authz solution): the access token
 * lives in memory and is invisible here, so we gate on the presence of the httpOnly
 * `refresh_token` cookie. No cookie -> not logged in -> bounce to /login. The real
 * authorization is enforced by the backend on every API call; a forged cookie just reaches
 * a page that then fails its data fetch with 401 and redirects.
 */
const PUBLIC_PATHS = ["/login"];

export function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const hasSession = request.cookies.has("refresh_token");
  const isPublic = PUBLIC_PATHS.some((p) => pathname === p || pathname.startsWith(p + "/"));

  if (!hasSession && !isPublic) {
    const url = request.nextUrl.clone();
    url.pathname = "/login";
    // Preserve where they were headed so login can send them back.
    url.searchParams.set("next", pathname);
    return NextResponse.redirect(url);
  }

  // Already logged in but sitting on /login -> send into the app.
  if (hasSession && isPublic) {
    const url = request.nextUrl.clone();
    url.pathname = "/pipeline";
    url.search = "";
    return NextResponse.redirect(url);
  }

  return NextResponse.next();
}

export const config = {
  // Run on everything except Next internals, the API proxy, and static assets.
  matcher: ["/((?!api|_next/static|_next/image|favicon.ico).*)"],
};
