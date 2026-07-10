import { ContactForm } from "@/components/forms/ContactForm";

export default function NewContactPage() {
  return (
    <div className="mx-auto max-w-2xl">
      <h1 className="mb-6 text-xl font-semibold tracking-tight">New contact</h1>
      <ContactForm />
    </div>
  );
}
