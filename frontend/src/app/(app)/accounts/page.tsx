"use client";

import { useState } from "react";
import Link from "next/link";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { Trash2 } from "lucide-react";
import { accountsApi } from "@/lib/api/endpoints";
import { Button } from "@/components/ui/button";
import { PageHeader } from "@/components/ui/page-header";
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
      <PageHeader
        eyebrow="Companies"
        title="Accounts"
        action={
          <Link href="/accounts/new">
            <Button size="sm">New account</Button>
          </Link>
        }
      />

      {isLoading ? (
        <p className="text-sm text-muted">Loading…</p>
      ) : isError || !data ? (
        <p className="text-sm text-danger">Couldn&apos;t load accounts.</p>
      ) : data.content.length === 0 ? (
        <p className="border-t border-hairline py-16 text-center text-sm text-muted">
          No accounts yet.
        </p>
      ) : (
        <>
          <div className="border-t border-hairline">
            <table className="w-full text-sm">
              <thead className="border-b border-hairline text-left">
                <tr className="[&>th]:eyebrow [&>th]:px-1 [&>th]:pb-2">
                  <th>Name</th>
                  <th>Industry</th>
                  <th>Employees</th>
                  <th>Website</th>
                  <th />
                </tr>
              </thead>
              <tbody className="divide-y divide-hairline">
                {data.content.map((a) => (
                  <tr key={a.id} className="transition-colors hover:bg-accent-soft/40">
                    <td className="px-1 py-3 font-medium text-ink">{a.name}</td>
                    <td className="px-1 py-3 text-muted">{a.industry ?? "—"}</td>
                    <td className="tabular px-1 py-3 text-muted">{a.employeeCount ?? "—"}</td>
                    <td className="px-1 py-3 text-muted">
                      {a.website ? (
                        <a href={a.website} className="text-accent underline-offset-4 hover:underline" target="_blank" rel="noreferrer">
                          {a.website}
                        </a>
                      ) : (
                        "—"
                      )}
                    </td>
                    <td className="px-1 py-3 text-right">
                      <button
                        className="text-faint transition-colors hover:text-danger"
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
