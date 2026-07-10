"use client";

import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useRouter } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { dealFormSchema, type DealFormValues } from "@/lib/forms/schemas";
import { dealsApi } from "@/lib/api/endpoints";
import { ApiError } from "@/lib/api/client";
import { useToast } from "@/components/ui/toast";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Field } from "@/components/ui/field";
import type { DealRequest } from "@/lib/api/schema";

/**
 * Create a deal (T6.9). Stage options come from the live pipeline stages. Optimistic-locking
 * note: edits (not shown here) send the deal's `version` and surface a 409 as "someone else
 * updated this, reload" rather than a generic error — same story as the Kanban conflict.
 */
export function DealForm() {
  const router = useRouter();
  const qc = useQueryClient();
  const { toast } = useToast();

  const { data: stages } = useQuery({
    queryKey: ["deal-stages"],
    queryFn: () => dealsApi.stages(),
  });

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<DealFormValues>({
    resolver: zodResolver(dealFormSchema),
    defaultValues: {
      stageId: "",
      leadId: "",
      accountId: "",
      ownerId: "",
      amount: "",
      currency: "USD",
      expectedCloseDate: "",
    },
  });

  async function onSubmit(values: DealFormValues) {
    const parsed = dealFormSchema.parse(values) as DealRequest;
    try {
      const deal = await dealsApi.create(parsed);
      qc.invalidateQueries({ queryKey: ["pipeline"] });
      toast("Deal created.", "success");
      router.push("/pipeline");
      void deal;
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        toast("Someone else updated this. Reload and try again.", "error");
      } else {
        toast(err instanceof ApiError ? err.message : "Couldn't create the deal.", "error");
      }
    }
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="max-w-md space-y-4">
      <Field label="Stage" htmlFor="stageId" error={errors.stageId?.message}>
        <select
          id="stageId"
          className="h-10 w-full rounded-md border border-hairline bg-surface px-3 text-sm"
          {...register("stageId")}
        >
          <option value="">Select a stage…</option>
          {stages?.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name}
            </option>
          ))}
        </select>
      </Field>

      <Field label="Amount" htmlFor="amount" error={errors.amount?.message}>
        <Input id="amount" type="number" min="0" step="1" {...register("amount")} />
      </Field>

      <Field label="Currency" htmlFor="currency" error={errors.currency?.message}>
        <Input id="currency" maxLength={3} {...register("currency")} />
      </Field>

      <Field label="Lead ID" htmlFor="leadId" error={errors.leadId?.message}>
        <Input id="leadId" placeholder="optional" {...register("leadId")} />
      </Field>

      <Field
        label="Expected close date"
        htmlFor="expectedCloseDate"
        error={errors.expectedCloseDate?.message}
      >
        <Input id="expectedCloseDate" type="date" {...register("expectedCloseDate")} />
      </Field>

      <div className="flex gap-2 pt-2">
        <Button type="submit" disabled={isSubmitting}>
          {isSubmitting ? "Creating…" : "Create deal"}
        </Button>
        <Button type="button" variant="ghost" onClick={() => router.back()}>
          Cancel
        </Button>
      </div>
    </form>
  );
}
