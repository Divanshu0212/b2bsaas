import { LeadForm } from "@/components/forms/LeadForm";

export default function NewLeadPage() {
  return (
    <div className="mx-auto max-w-2xl">
      <h1 className="mb-6 text-xl font-semibold tracking-tight">New lead</h1>
      <LeadForm />
    </div>
  );
}
