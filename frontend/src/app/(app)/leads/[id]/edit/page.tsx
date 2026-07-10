"use client";

import { use } from "react";
import { useQuery } from "@tanstack/react-query";
import { leadsApi } from "@/lib/api/endpoints";
import { LeadForm } from "@/components/forms/LeadForm";

export default function EditLeadPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { data: lead, isLoading } = useQuery({
    queryKey: ["lead", id],
    queryFn: () => leadsApi.get(id),
  });

  return (
    <div className="mx-auto max-w-2xl">
      <h1 className="mb-6 text-xl font-semibold tracking-tight">Edit lead</h1>
      {isLoading || !lead ? (
        <p className="text-sm text-muted">Loading…</p>
      ) : (
        <LeadForm existing={lead} />
      )}
    </div>
  );
}
