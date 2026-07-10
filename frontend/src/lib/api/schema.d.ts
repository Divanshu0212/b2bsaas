/*
 * Typed API contract for the SalesPipe backend.
 *
 * REGENERATE, don't hand-edit: `npm run gen:api` runs openapi-typescript against a running
 * backend's /v3/api-docs and overwrites this file. CI regenerates + diffs so frontend/backend
 * drift becomes a failed check (T6.11 contract test). This checked-in copy mirrors the
 * Phase 1-6 controllers so the app type-checks without a live backend during local dev.
 */

export type UUID = string;
/** ISO-8601 timestamp (OffsetDateTime on the backend). */
export type Instant = string;
/** ISO date (yyyy-MM-dd). */
export type IsoDate = string;

export type Role = "ADMIN" | "SALES_REP";

export interface AccessTokenResponse {
  accessToken: string;
}

export interface RegisterRequest {
  orgName: string;
  email: string;
  password: string;
}
export interface LoginRequest {
  email: string;
  password: string;
}

export interface LeadRequest {
  accountId: UUID | null;
  contactId: UUID | null;
  name: string;
  email: string | null;
  status: string;
  ownerId: UUID | null;
}
export interface LeadResponse {
  id: UUID;
  accountId: UUID | null;
  contactId: UUID | null;
  name: string;
  email: string | null;
  status: string;
  ownerId: UUID | null;
  createdAt: Instant;
}

export interface AccountRequest {
  name: string;
  domain: string | null;
}
export interface AccountResponse {
  id: UUID;
  name: string;
  domain: string | null;
}

export interface ContactRequest {
  accountId: UUID | null;
  name: string;
  email: string | null;
  phone: string | null;
}
export interface ContactResponse {
  id: UUID;
  accountId: UUID | null;
  name: string;
  email: string | null;
  phone: string | null;
}

export interface DealStage {
  id: UUID;
  name: string;
  position: number;
  isWon: boolean;
  isLost: boolean;
}

export interface DealRequest {
  leadId: UUID | null;
  accountId: UUID | null;
  stageId: UUID;
  ownerId: UUID | null;
  amount: number | null;
  currency: string | null;
  expectedCloseDate: IsoDate | null;
}
export interface DealResponse {
  id: UUID;
  leadId: UUID | null;
  accountId: UUID | null;
  stageId: UUID;
  ownerId: UUID | null;
  amount: number | null;
  currency: string | null;
  expectedCloseDate: IsoDate | null;
  version: number;
}

/** One column of the Kanban board: a stage plus the deals currently in it. */
export interface PipelineColumn {
  stage: DealStage;
  deals: PipelineCard[];
}
export interface PipelineCard {
  id: UUID;
  leadId: UUID | null;
  leadName: string | null;
  accountName: string | null;
  ownerId: UUID | null;
  amount: number | null;
  currency: string | null;
  score: number | null;
  version: number;
}

/** PATCH /deals/{id}/stage body — carries the optimistic-lock version. */
export interface StageChangeRequest {
  toStageId: UUID;
  version: number;
}

export interface ScoreFactor {
  feature: string;
  contribution: number;
}
export interface ScorePoint {
  score: number;
  modelVersion: string | null;
  topFactors: ScoreFactor[];
  scoredAt: Instant;
}
export interface ScoreResponse {
  leadId: UUID;
  latest: ScorePoint | null;
  history: ScorePoint[];
}

export type ActivityType =
  | "LEAD_CREATED"
  | "ACTIVITY_LOGGED"
  | "DEAL_STAGE_CHANGED"
  | "EMAIL_OPENED"
  | "EMAIL_CLICKED";
export interface TimelineEntry {
  id: UUID;
  type: ActivityType;
  summary: string;
  occurredAt: Instant;
}
export interface TimelinePage {
  entries: TimelineEntry[];
  nextCursor: string | null;
}

export interface NotificationItem {
  id: UUID;
  type: string;
  payload: Record<string, unknown>;
  readAt: Instant | null;
  createdAt: Instant;
}
export interface NotificationList {
  items: NotificationItem[];
  unreadCount: number;
}

export interface FunnelStage {
  stageId: UUID;
  stageName: string;
  position: number;
  dealCount: number;
  totalAmount: number;
}
export interface LeaderboardRow {
  ownerId: UUID;
  ownerEmail: string;
  dealCount: number;
  totalAmount: number;
}
export interface FunnelReport {
  funnel: FunnelStage[];
  leaderboard: LeaderboardRow[];
}

/** RFC 7807 problem+json — the backend's error shape. */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
