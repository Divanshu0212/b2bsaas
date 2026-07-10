"use client";

import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { leadFormSchema, type LeadFormValues } from "@/lib/forms/schemas";
import { leadsApi } from "@/lib/api/endpoints";
import { ApiError } from "@/lib/api/client";
import { useToast } from "@/components/ui/toast";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Field } from "@/components/ui/field";
import type { LeadRequest, LeadResponse, LeadStatus } from "@/lib/api/schema";

const STATUSES: LeadStatus[] = ["NEW", "CONTACTED", "QUALIFIED", "UNQUALIFIED", "CONVERTED"];

/** Create (no `existing`) or edit (pass the loaded lead) a lead with the same form. */
export function LeadForm({ existing }: { existing?: LeadResponse }) {
  const router = useRouter();
  const qc = useQueryClient();
  const { toast } = useToast();
  const editing = existing !== undefined;

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<LeadFormValues>({
    resolver: zodResolver(leadFormSchema),
    defaultValues: existing
      ? {
          status: existing.status,
          source: existing.source ?? "",
          rawNotes: existing.rawNotes ?? "",
          contactId: existing.contactId ?? "",
          accountId: existing.accountId ?? "",
          ownerId: existing.ownerId ?? "",
        }
      : { status: "NEW", source: "", rawNotes: "", contactId: "", accountId: "", ownerId: "" },
  });

  async function onSubmit(values: LeadFormValues) {
    // zod transforms "" -> null already; cast to the request shape.
    const parsed = leadFormSchema.parse(values) as LeadRequest;
    try {
      if (editing) {
        await leadsApi.update(existing.id, parsed);
        qc.invalidateQueries({ queryKey: ["lead", existing.id] });
        qc.invalidateQueries({ queryKey: ["leads"] });
        toast("Lead updated.", "success");
        router.push(`/leads/${existing.id}`);
      } else {
        const lead = await leadsApi.create(parsed);
        qc.invalidateQueries({ queryKey: ["leads"] });
        toast("Lead created.", "success");
        router.push(`/leads/${lead.id}`);
      }
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        toast("Someone else updated this lead. Reload and try again.", "error");
      } else {
        toast(err instanceof ApiError ? err.message : "Couldn't save the lead.", "error");
      }
    }
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="max-w-md space-y-4">
      <Field label="Status" htmlFor="status" error={errors.status?.message}>
        <select
          id="status"
          className="h-10 w-full rounded-md border border-hairline bg-surface px-3 text-sm"
          {...register("status")}
        >
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {s.toLowerCase()}
            </option>
          ))}
        </select>
      </Field>

      <Field label="Source" htmlFor="source" error={errors.source?.message}>
        <Input id="source" placeholder="e.g. webinar, referral" {...register("source")} />
      </Field>

      <Field label="Notes" htmlFor="rawNotes" error={errors.rawNotes?.message}>
        <Input id="rawNotes" {...register("rawNotes")} />
      </Field>

      <Field label="Account ID" htmlFor="accountId" error={errors.accountId?.message}>
        <Input id="accountId" placeholder="optional" {...register("accountId")} />
      </Field>

      <div className="flex gap-2 pt-2">
        <Button type="submit" disabled={isSubmitting}>
          {isSubmitting ? "Saving…" : editing ? "Save changes" : "Create lead"}
        </Button>
        <Button type="button" variant="ghost" onClick={() => router.back()}>
          Cancel
        </Button>
      </div>
    </form>
  );
}
