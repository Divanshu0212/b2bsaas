"use client";

import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { accountFormSchema, type AccountFormValues } from "@/lib/forms/schemas";
import { accountsApi } from "@/lib/api/endpoints";
import { ApiError } from "@/lib/api/client";
import { useToast } from "@/components/ui/toast";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Field } from "@/components/ui/field";
import type { AccountRequest } from "@/lib/api/schema";

export function AccountForm() {
  const router = useRouter();
  const qc = useQueryClient();
  const { toast } = useToast();

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<AccountFormValues>({
    resolver: zodResolver(accountFormSchema),
    defaultValues: { name: "", industry: "", employeeCount: "", website: "" },
  });

  async function onSubmit(values: AccountFormValues) {
    const parsed = accountFormSchema.parse(values) as AccountRequest;
    try {
      await accountsApi.create(parsed);
      qc.invalidateQueries({ queryKey: ["accounts"] });
      toast("Account created.", "success");
      router.push("/accounts");
    } catch (err) {
      toast(err instanceof ApiError ? err.message : "Couldn't create the account.", "error");
    }
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="max-w-md space-y-4">
      <Field label="Name" htmlFor="name" error={errors.name?.message}>
        <Input id="name" {...register("name")} />
      </Field>
      <Field label="Industry" htmlFor="industry" error={errors.industry?.message}>
        <Input id="industry" {...register("industry")} />
      </Field>
      <Field label="Employees" htmlFor="employeeCount" error={errors.employeeCount?.message}>
        <Input id="employeeCount" type="number" min="1" {...register("employeeCount")} />
      </Field>
      <Field label="Website" htmlFor="website" error={errors.website?.message}>
        <Input id="website" placeholder="https://…" {...register("website")} />
      </Field>
      <div className="flex gap-2 pt-2">
        <Button type="submit" disabled={isSubmitting}>
          {isSubmitting ? "Creating…" : "Create account"}
        </Button>
        <Button type="button" variant="ghost" onClick={() => router.back()}>
          Cancel
        </Button>
      </div>
    </form>
  );
}
