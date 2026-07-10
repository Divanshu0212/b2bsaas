import { DealForm } from "@/components/forms/DealForm";
import { PageHeader } from "@/components/ui/page-header";

export default function NewDealPage() {
  return (
    <div className="mx-auto max-w-2xl">
      <PageHeader eyebrow="New record" title="New deal" />
      <DealForm />
    </div>
  );
}
