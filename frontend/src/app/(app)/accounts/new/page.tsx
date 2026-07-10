import { AccountForm } from "@/components/forms/AccountForm";

export default function NewAccountPage() {
  return (
    <div className="mx-auto max-w-2xl">
      <h1 className="mb-6 text-xl font-semibold tracking-tight">New account</h1>
      <AccountForm />
    </div>
  );
}
