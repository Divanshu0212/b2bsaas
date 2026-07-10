"use client";

import { useState } from "react";
import Link from "next/link";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { Trash2 } from "lucide-react";
import { contactsApi } from "@/lib/api/endpoints";
import { Button } from "@/components/ui/button";
import { PageHeader } from "@/components/ui/page-header";
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
      <PageHeader
        eyebrow="People"
        title="Contacts"
        action={
          <Link href="/contacts/new">
            <Button size="sm">New contact</Button>
          </Link>
        }
      />

      {isLoading ? (
        <p className="text-sm text-muted">Loading…</p>
      ) : isError || !data ? (
        <p className="text-sm text-danger">Couldn&apos;t load contacts.</p>
      ) : data.content.length === 0 ? (
        <p className="border-t border-hairline py-16 text-center text-sm text-muted">
          No contacts yet.
        </p>
      ) : (
        <>
          <div className="border-t border-hairline">
            <table className="w-full text-sm">
              <thead className="border-b border-hairline text-left">
                <tr className="[&>th]:eyebrow [&>th]:px-1 [&>th]:pb-2">
                  <th>Name</th>
                  <th>Title</th>
                  <th>Email</th>
                  <th>Phone</th>
                  <th />
                </tr>
              </thead>
              <tbody className="divide-y divide-hairline">
                {data.content.map((c) => (
                  <tr key={c.id} className="transition-colors hover:bg-accent-soft/40">
                    <td className="px-1 py-3 font-medium text-ink">{fullName(c.firstName, c.lastName)}</td>
                    <td className="px-1 py-3 text-muted">{c.title ?? "—"}</td>
                    <td className="px-1 py-3 text-muted">{c.email ?? "—"}</td>
                    <td className="tabular px-1 py-3 text-muted">{c.phone ?? "—"}</td>
                    <td className="px-1 py-3 text-right">
                      <button
                        className="text-faint transition-colors hover:text-danger"
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
