import { ContactForm } from "@/components/forms/ContactForm";
import { PageHeader } from "@/components/ui/page-header";

export default function NewContactPage() {
  return (
    <div className="mx-auto max-w-2xl">
      <PageHeader eyebrow="New record" title="New contact" />
      <ContactForm />
    </div>
  );
}
