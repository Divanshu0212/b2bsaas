"use client";

import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { dlqApi } from "@/lib/api/endpoints";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/toast";

/**
 * Dead-letter management (ADMIN). Pick a DLQ topic, see what's stuck with the failure reason,
 * and replay a message back to its original topic. Backed by the Phase 4 DLQ admin API
 * (@PreAuthorize ADMIN) — a SALES_REP gets 403 (and the nav link is hidden anyway).
 */
export default function DlqAdminPage() {
  const qc = useQueryClient();
  const { toast } = useToast();
  const [topic, setTopic] = useState<string>("");

  const { data: topics } = useQuery({
    queryKey: ["dlq-topics"],
    queryFn: () => dlqApi.topics(),
  });

  const { data: messages, isLoading } = useQuery({
    queryKey: ["dlq", topic],
    queryFn: () => dlqApi.list(topic),
    enabled: topic !== "",
  });

  const replay = useMutation({
    mutationFn: (m: { topic: string; partition: number; offset: number }) => dlqApi.replay(m),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["dlq", topic] });
      toast("Message replayed to its original topic.", "success");
    },
    onError: () => toast("Replay failed.", "error"),
  });

  return (
    <div>
      <h1 className="mb-1 text-xl font-semibold tracking-tight">Dead letters</h1>
      <p className="mb-4 text-sm text-muted">
        Messages that failed processing after retries. Replay pushes one back to its origin topic.
      </p>

      <div className="mb-4 max-w-xs">
        <label htmlFor="topic" className="mb-1 block text-sm text-muted">
          DLQ topic
        </label>
        <select
          id="topic"
          className="h-10 w-full rounded-md border border-hairline bg-surface px-3 text-sm"
          value={topic}
          onChange={(e) => setTopic(e.target.value)}
        >
          <option value="">Select a topic…</option>
          {topics?.map((t) => (
            <option key={t} value={t}>
              {t}
            </option>
          ))}
        </select>
      </div>

      {topic === "" ? (
        <p className="text-sm text-muted">Pick a topic to inspect its dead letters.</p>
      ) : isLoading ? (
        <p className="text-sm text-muted">Loading…</p>
      ) : !messages || messages.length === 0 ? (
        <p className="rounded-md border border-hairline bg-surface p-8 text-center text-sm text-muted">
          Nothing stuck in this topic.
        </p>
      ) : (
        <div className="overflow-x-auto rounded-md border border-hairline bg-surface">
          <table className="w-full text-sm">
            <thead className="border-b border-hairline text-left text-xs uppercase tracking-wide text-muted">
              <tr>
                <th className="px-4 py-2 font-medium">Origin</th>
                <th className="px-4 py-2 font-medium">Part/Offset</th>
                <th className="px-4 py-2 font-medium">Attempts</th>
                <th className="px-4 py-2 font-medium">Reason</th>
                <th className="px-4 py-2" />
              </tr>
            </thead>
            <tbody className="divide-y divide-hairline">
              {messages.map((m) => (
                <tr key={`${m.partition}-${m.offset}`} className="hover:bg-hairline/30">
                  <td className="px-4 py-2.5 text-ink">{m.originalTopic}</td>
                  <td className="tabular px-4 py-2.5 text-muted">
                    {m.partition}/{m.offset}
                  </td>
                  <td className="tabular px-4 py-2.5 text-muted">{m.attempts ?? "—"}</td>
                  <td className="max-w-sm truncate px-4 py-2.5 text-muted" title={m.failureReason ?? ""}>
                    {m.failureReason ?? "—"}
                  </td>
                  <td className="px-4 py-2.5 text-right">
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={replay.isPending}
                      onClick={() =>
                        replay.mutate({ topic: m.dlqTopic, partition: m.partition, offset: m.offset })
                      }
                    >
                      Replay
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
