"use client";

import { useState } from "react";
import Link from "next/link";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { Trash2 } from "lucide-react";
import { contactsApi } from "@/lib/api/endpoints";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/toast";

function fullName(first: string | null, last: string | null): string {
  const n = [first, last].filter(Boolean).join(" ");
  return n || "—";
}

export default function ContactsPage() {
  const [page, setPage] = useState(0);
  const qc = useQueryClient();
  const { toast } = useToast();

  const { data, isLoading, isError } = useQuery({
    queryKey: ["contacts", page],
    queryFn: () => contactsApi.list(page),
    placeholderData: keepPreviousData,
  });

  const remove = useMutation({
    mutationFn: (id: string) => contactsApi.remove(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["contacts"] });
      toast("Contact deleted.", "success");
    },
    onError: () => toast("Couldn't delete the contact.", "error"),
  });

  return (
    <div>
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-xl font-semibold tracking-tight">Contacts</h1>
        <Link href="/contacts/new">
          <Button size="sm">New contact</Button>
        </Link>
      </div>

      {isLoading ? (
        <p className="text-sm text-muted">Loading…</p>
      ) : isError || !data ? (
        <p className="text-sm text-danger">Couldn&apos;t load contacts.</p>
      ) : data.content.length === 0 ? (
        <p className="rounded-md border border-hairline bg-surface p-8 text-center text-sm text-muted">
          No contacts yet.
        </p>
      ) : (
        <>
          <div className="overflow-hidden rounded-md border border-hairline bg-surface">
            <table className="w-full text-sm">
              <thead className="border-b border-hairline text-left text-xs uppercase tracking-wide text-muted">
                <tr>
                  <th className="px-4 py-2 font-medium">Name</th>
                  <th className="px-4 py-2 font-medium">Title</th>
                  <th className="px-4 py-2 font-medium">Email</th>
                  <th className="px-4 py-2 font-medium">Phone</th>
                  <th className="px-4 py-2" />
                </tr>
              </thead>
              <tbody className="divide-y divide-hairline">
                {data.content.map((c) => (
                  <tr key={c.id} className="hover:bg-hairline/30">
                    <td className="px-4 py-2.5 text-ink">{fullName(c.firstName, c.lastName)}</td>
                    <td className="px-4 py-2.5 text-muted">{c.title ?? "—"}</td>
                    <td className="px-4 py-2.5 text-muted">{c.email ?? "—"}</td>
                    <td className="tabular px-4 py-2.5 text-muted">{c.phone ?? "—"}</td>
                    <td className="px-4 py-2.5 text-right">
                      <button
                        className="text-faint hover:text-danger"
                        aria-label="Delete contact"
                        onClick={() => {
                          if (confirm("Delete this contact?")) remove.mutate(c.id);
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
