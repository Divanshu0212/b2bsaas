"use client";

import { use } from "react";
import { useQuery } from "@tanstack/react-query";
import { leadsApi } from "@/lib/api/endpoints";
import { LeadForm } from "@/components/forms/LeadForm";
import { PageHeader } from "@/components/ui/page-header";

export default function EditLeadPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { data: lead, isLoading } = useQuery({
    queryKey: ["lead", id],
    queryFn: () => leadsApi.get(id),
  });

  return (
    <div className="mx-auto max-w-2xl">
      <PageHeader eyebrow="Editing" title="Edit lead" />
      {isLoading || !lead ? (
        <p className="text-sm text-muted">Loading…</p>
      ) : (
        <LeadForm existing={lead} />
      )}
    </div>
  );
}
