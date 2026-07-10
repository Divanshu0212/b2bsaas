import { getAccessToken } from "./token-store";
import type { Role, UUID } from "@/lib/api/schema";

/** Claims we read from the access JWT (set by the backend JwtProvider). */
export interface Session {
  userId: UUID;
  orgId: UUID;
  role: Role;
}

/** Decode a base64url JWT payload without a library (no signature check — display only;
 *  the backend verifies the signature on every request). Returns null if unreadable. */
function decodeJwt(token: string): Record<string, unknown> | null {
  const part = token.split(".")[1];
  if (!part) return null;
  try {
    const json = atob(part.replace(/-/g, "+").replace(/_/g, "/"));
    return JSON.parse(json) as Record<string, unknown>;
  } catch {
    return null;
  }
}

/** Current session derived from the in-memory access token, or null if not signed in. */
export function getSession(): Session | null {
  const token = getAccessToken();
  if (!token) return null;
  const claims = decodeJwt(token);
  if (!claims) return null;
  const role = claims.role;
  const userId = claims.sub;
  const orgId = claims.org_id;
  if (typeof role !== "string" || typeof userId !== "string" || typeof orgId !== "string") {
    return null;
  }
  return { userId, orgId, role: role as Role };
}
