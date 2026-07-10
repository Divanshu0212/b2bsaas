import { LeadForm } from "@/components/forms/LeadForm";
import { PageHeader } from "@/components/ui/page-header";

export default function NewLeadPage() {
  return (
    <div className="mx-auto max-w-2xl">
      <PageHeader eyebrow="New record" title="New lead" />
      <LeadForm />
    </div>
  );
}
