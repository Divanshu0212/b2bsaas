import { apiFetch } from "./client";
import { setAccessToken, clearAccessToken } from "@/lib/auth/token-store";
import type {
  AccessTokenResponse,
  AccountRequest,
  AccountResponse,
  ActivityResponse,
  ContactRequest,
  ContactResponse,
  DealRequest,
  DealResponse,
  DealStage,
  DlqMessage,
  FunnelReport,
  LeadRequest,
  LeadResponse,
  LeadStatus,
  LoginRequest,
  NotificationList,
  PageResponse,
  StageColumn,
  RegisterRequest,
  ScoreResponse,
  StageChangeRequest,
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
  pipeline: () => apiFetch<StageColumn[]>("/deals/pipeline"),
  stages: () => apiFetch<DealStage[]>("/deal-stages"),
  changeStage: (id: UUID, body: StageChangeRequest) =>
    apiFetch<DealResponse>(`/deals/${id}/stage`, { method: "PATCH", body }),
  get: (id: UUID) => apiFetch<DealResponse>(`/deals/${id}`),
  create: (body: DealRequest) =>
    apiFetch<DealResponse>("/deals", { method: "POST", body }),
};

// ---- leads ----
export const leadsApi = {
  list: (params: { status?: LeadStatus; owner?: string; page?: number; size?: number }) => {
    const q = new URLSearchParams();
    if (params.status) q.set("status", params.status);
    if (params.owner) q.set("owner", params.owner);
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
  timeline: (id: UUID, page = 0, size = 20) =>
    apiFetch<PageResponse<ActivityResponse>>(`/leads/${id}/timeline?page=${page}&size=${size}`),
};

// ---- accounts / contacts ----
export const accountsApi = {
  list: (page = 0, size = 20) =>
    apiFetch<PageResponse<AccountResponse>>(`/accounts?page=${page}&size=${size}`),
  create: (body: AccountRequest) =>
    apiFetch<AccountResponse>("/accounts", { method: "POST", body }),
  get: (id: UUID) => apiFetch<AccountResponse>(`/accounts/${id}`),
  remove: (id: UUID) => apiFetch<void>(`/accounts/${id}`, { method: "DELETE" }),
};
export const contactsApi = {
  list: (page = 0, size = 20) =>
    apiFetch<PageResponse<ContactResponse>>(`/contacts?page=${page}&size=${size}`),
  create: (body: ContactRequest) =>
    apiFetch<ContactResponse>("/contacts", { method: "POST", body }),
  get: (id: UUID) => apiFetch<ContactResponse>(`/contacts/${id}`),
  remove: (id: UUID) => apiFetch<void>(`/contacts/${id}`, { method: "DELETE" }),
};

// ---- DLQ admin (ADMIN role) ----
export const dlqApi = {
  topics: () => apiFetch<string[]>("/admin/dlq/topics"),
  list: (topic: string, limit = 50) =>
    apiFetch<DlqMessage[]>(`/admin/dlq?topic=${encodeURIComponent(topic)}&limit=${limit}`),
  count: (topic: string) =>
    apiFetch<{ count: number }>(`/admin/dlq/count?topic=${encodeURIComponent(topic)}`),
  replay: (body: { topic: string; partition: number; offset: number }) =>
    apiFetch<Record<string, unknown>>("/admin/dlq/replay", { method: "POST", body }),
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
