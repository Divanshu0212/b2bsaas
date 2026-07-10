import { DealForm } from "@/components/forms/DealForm";

export default function NewDealPage() {
  return (
    <div className="mx-auto max-w-2xl">
      <h1 className="mb-6 text-xl font-semibold tracking-tight">New deal</h1>
      <DealForm />
    </div>
  );
}
