"use client";

import { useState } from "react";
import Link from "next/link";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { Trash2 } from "lucide-react";
import { accountsApi } from "@/lib/api/endpoints";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/toast";

export default function AccountsPage() {
  const [page, setPage] = useState(0);
  const qc = useQueryClient();
  const { toast } = useToast();

  const { data, isLoading, isError } = useQuery({
    queryKey: ["accounts", page],
    queryFn: () => accountsApi.list(page),
    placeholderData: keepPreviousData,
  });

  const remove = useMutation({
    mutationFn: (id: string) => accountsApi.remove(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["accounts"] });
      toast("Account deleted.", "success");
    },
    onError: () => toast("Couldn't delete the account.", "error"),
  });

  return (
    <div>
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-xl font-semibold tracking-tight">Accounts</h1>
        <Link href="/accounts/new">
          <Button size="sm">New account</Button>
        </Link>
      </div>

      {isLoading ? (
        <p className="text-sm text-muted">Loading…</p>
      ) : isError || !data ? (
        <p className="text-sm text-danger">Couldn&apos;t load accounts.</p>
      ) : data.content.length === 0 ? (
        <p className="rounded-md border border-hairline bg-surface p-8 text-center text-sm text-muted">
          No accounts yet.
        </p>
      ) : (
        <>
          <div className="overflow-hidden rounded-md border border-hairline bg-surface">
            <table className="w-full text-sm">
              <thead className="border-b border-hairline text-left text-xs uppercase tracking-wide text-muted">
                <tr>
                  <th className="px-4 py-2 font-medium">Name</th>
                  <th className="px-4 py-2 font-medium">Industry</th>
                  <th className="px-4 py-2 font-medium">Employees</th>
                  <th className="px-4 py-2 font-medium">Website</th>
                  <th className="px-4 py-2" />
                </tr>
              </thead>
              <tbody className="divide-y divide-hairline">
                {data.content.map((a) => (
                  <tr key={a.id} className="hover:bg-hairline/30">
                    <td className="px-4 py-2.5 text-ink">{a.name}</td>
                    <td className="px-4 py-2.5 text-muted">{a.industry ?? "—"}</td>
                    <td className="tabular px-4 py-2.5 text-muted">{a.employeeCount ?? "—"}</td>
                    <td className="px-4 py-2.5 text-muted">
                      {a.website ? (
                        <a href={a.website} className="text-accent hover:underline" target="_blank" rel="noreferrer">
                          {a.website}
                        </a>
                      ) : (
                        "—"
                      )}
                    </td>
                    <td className="px-4 py-2.5 text-right">
                      <button
                        className="text-faint hover:text-danger"
                        aria-label="Delete account"
                        onClick={() => {
                          if (confirm(`Delete "${a.name}"?`)) remove.mutate(a.id);
                        }}
                      >
                        <Trash2 size={16} />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="mt-3 flex items-center justify-between text-sm text-muted">
            <span className="tabular">
              Page {data.number + 1} of {Math.max(1, data.totalPages)} · {data.totalElements} total
            </span>
            <div className="flex gap-2">
              <Button size="sm" variant="outline" disabled={data.first} onClick={() => setPage((p) => Math.max(0, p - 1))}>
                Previous
              </Button>
              <Button size="sm" variant="outline" disabled={data.last} onClick={() => setPage((p) => p + 1)}>
                Next
              </Button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}
