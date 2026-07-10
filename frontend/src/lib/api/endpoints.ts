import { apiFetch } from "./client";
import { setAccessToken, clearAccessToken } from "@/lib/auth/token-store";
import type {
  AccessTokenResponse,
  AccountRequest,
  AccountResponse,
  ContactRequest,
  ContactResponse,
  DealRequest,
  DealResponse,
  DealStage,
  FunnelReport,
  LeadRequest,
  LeadResponse,
  LoginRequest,
  NotificationList,
  PageResponse,
  PipelineColumn,
  RegisterRequest,
  ScoreResponse,
  StageChangeRequest,
  TimelinePage,
  UUID,
} from "./schema";

// ---- auth ----
export const authApi = {
  async login(body: LoginRequest): Promise<void> {
    const res = await apiFetch<AccessTokenResponse>("/auth/login", {
      method: "POST",
      body,
      retryOnUnauthorized: false,
    });
    setAccessToken(res.accessToken);
  },
  async register(body: RegisterRequest): Promise<void> {
    const res = await apiFetch<AccessTokenResponse>("/auth/register", {
      method: "POST",
      body,
      retryOnUnauthorized: false,
    });
    setAccessToken(res.accessToken);
  },
  async logout(): Promise<void> {
    await apiFetch<void>("/auth/logout", { method: "POST", retryOnUnauthorized: false });
    clearAccessToken();
  },
};

// ---- pipeline / deals ----
export const dealsApi = {
  pipeline: () => apiFetch<PipelineColumn[]>("/deals/pipeline"),
  stages: () => apiFetch<DealStage[]>("/deal-stages"),
  changeStage: (id: UUID, body: StageChangeRequest) =>
    apiFetch<DealResponse>(`/deals/${id}/stage`, { method: "PATCH", body }),
  get: (id: UUID) => apiFetch<DealResponse>(`/deals/${id}`),
  create: (body: DealRequest) =>
    apiFetch<DealResponse>("/deals", { method: "POST", body }),
};

// ---- leads ----
export const leadsApi = {
  list: (params: { status?: string; ownerId?: string; page?: number; size?: number }) => {
    const q = new URLSearchParams();
    if (params.status) q.set("status", params.status);
    if (params.ownerId) q.set("ownerId", params.ownerId);
    q.set("page", String(params.page ?? 0));
    q.set("size", String(params.size ?? 20));
    return apiFetch<PageResponse<LeadResponse>>(`/leads?${q.toString()}`);
  },
  get: (id: UUID) => apiFetch<LeadResponse>(`/leads/${id}`),
  create: (body: LeadRequest) => apiFetch<LeadResponse>("/leads", { method: "POST", body }),
  update: (id: UUID, body: LeadRequest) =>
    apiFetch<LeadResponse>(`/leads/${id}`, { method: "PUT", body }),
  score: (id: UUID) => apiFetch<ScoreResponse>(`/leads/${id}/score`),
  refreshScore: (id: UUID) =>
    apiFetch<ScoreResponse>(`/leads/${id}/score/refresh`, { method: "POST" }),
  timeline: (id: UUID, cursor?: string) => {
    const q = cursor ? `?cursor=${encodeURIComponent(cursor)}` : "";
    return apiFetch<TimelinePage>(`/leads/${id}/timeline${q}`);
  },
};

// ---- accounts / contacts ----
export const accountsApi = {
  create: (body: AccountRequest) =>
    apiFetch<AccountResponse>("/accounts", { method: "POST", body }),
  get: (id: UUID) => apiFetch<AccountResponse>(`/accounts/${id}`),
};
export const contactsApi = {
  create: (body: ContactRequest) =>
    apiFetch<ContactResponse>("/contacts", { method: "POST", body }),
};

// ---- notifications ----
export const notificationsApi = {
  list: (limit = 50) => apiFetch<NotificationList>(`/notifications?limit=${limit}`),
  markRead: (id: UUID) =>
    apiFetch<void>(`/notifications/${id}/read`, { method: "POST" }),
};

// ---- reports ----
export const reportsApi = {
  funnel: () => apiFetch<FunnelReport>("/reports/funnel"),
};
