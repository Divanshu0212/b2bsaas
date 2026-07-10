import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { ScorePanel } from "@/components/leads/ScorePanel";
import type { ScoreResponse } from "@/lib/api/schema";

// Mock the API layer so the panel renders against a fixed score payload.
vi.mock("@/lib/api/endpoints", () => ({
  leadsApi: {
    score: vi.fn(),
    refreshScore: vi.fn(),
  },
}));
// Toast provider isn't mounted in the test tree.
vi.mock("@/components/ui/toast", () => ({ useToast: () => ({ toast: vi.fn() }) }));

import { leadsApi } from "@/lib/api/endpoints";

// recharts' ResponsiveContainer needs a measurable box in jsdom.
beforeEach(() => {
  Object.defineProperty(HTMLElement.prototype, "offsetWidth", { configurable: true, value: 400 });
  Object.defineProperty(HTMLElement.prototype, "offsetHeight", { configurable: true, value: 100 });
});

function wrapper({ children }: { children: ReactNode }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

const payload: ScoreResponse = {
  leadId: "lead-1",
  latest: {
    score: 0.82,
    modelVersion: "v3",
    topFactors: [
      { feature: "email_click_count", impact: 0.31 },
      { feature: "company_size_ordinal", impact: -0.05 },
    ],
    scoredAt: "2026-07-10T00:00:00Z",
  },
  history: [
    { score: 0.82, modelVersion: "v3", topFactors: [], scoredAt: "2026-07-10T00:00:00Z" },
    { score: 0.5, modelVersion: "v3", topFactors: [], scoredAt: "2026-07-09T00:00:00Z" },
  ],
};

describe("ScorePanel", () => {
  it("renders the score band and top SHAP factors", async () => {
    vi.mocked(leadsApi.score).mockResolvedValue(payload);

    render(<ScorePanel leadId="lead-1" />, { wrapper });

    // 0.82 -> "Hot" band, displayed as 82
    await waitFor(() => expect(screen.getByText("82")).toBeInTheDocument());
    expect(screen.getByText("Hot")).toBeInTheDocument();
    // SHAP factors surfaced (feature name humanized)
    expect(screen.getByText("email click count")).toBeInTheDocument();
    expect(screen.getByText("+0.31")).toBeInTheDocument();
  });
});
