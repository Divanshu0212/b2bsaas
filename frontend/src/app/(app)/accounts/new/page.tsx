import { AccountForm } from "@/components/forms/AccountForm";
import { PageHeader } from "@/components/ui/page-header";

export default function NewAccountPage() {
  return (
    <div className="mx-auto max-w-2xl">
      <PageHeader eyebrow="New record" title="New account" />
      <AccountForm />
    </div>
  );
}
