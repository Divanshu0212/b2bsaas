"use client";

import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useRouter } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { contactFormSchema, type ContactFormValues } from "@/lib/forms/schemas";
import { contactsApi, accountsApi } from "@/lib/api/endpoints";
import { ApiError } from "@/lib/api/client";
import { useToast } from "@/components/ui/toast";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Field } from "@/components/ui/field";
import type { ContactRequest } from "@/lib/api/schema";

export function ContactForm() {
  const router = useRouter();
  const qc = useQueryClient();
  const { toast } = useToast();

  // Account picker options (first page is enough for the common case).
  const { data: accounts } = useQuery({
    queryKey: ["accounts", 0],
    queryFn: () => accountsApi.list(0, 100),
  });

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<ContactFormValues>({
    resolver: zodResolver(contactFormSchema),
    defaultValues: { accountId: "", firstName: "", lastName: "", email: "", phone: "", title: "" },
  });

  async function onSubmit(values: ContactFormValues) {
    const parsed = contactFormSchema.parse(values) as ContactRequest;
    try {
      await contactsApi.create(parsed);
      qc.invalidateQueries({ queryKey: ["contacts"] });
      toast("Contact created.", "success");
      router.push("/contacts");
    } catch (err) {
      toast(err instanceof ApiError ? err.message : "Couldn't create the contact.", "error");
    }
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="max-w-md space-y-4">
      <div className="grid grid-cols-2 gap-3">
        <Field label="First name" htmlFor="firstName" error={errors.firstName?.message}>
          <Input id="firstName" {...register("firstName")} />
        </Field>
        <Field label="Last name" htmlFor="lastName" error={errors.lastName?.message}>
          <Input id="lastName" {...register("lastName")} />
        </Field>
      </div>
      <Field label="Email" htmlFor="email" error={errors.email?.message}>
        <Input id="email" type="email" {...register("email")} />
      </Field>
      <Field label="Phone" htmlFor="phone" error={errors.phone?.message}>
        <Input id="phone" {...register("phone")} />
      </Field>
      <Field label="Title" htmlFor="title" error={errors.title?.message}>
        <Input id="title" {...register("title")} />
      </Field>
      <Field label="Account" htmlFor="accountId" error={errors.accountId?.message}>
        <select
          id="accountId"
          className="h-10 w-full rounded-md border border-hairline bg-surface px-3 text-sm"
          {...register("accountId")}
        >
          <option value="">No account</option>
          {accounts?.content.map((a) => (
            <option key={a.id} value={a.id}>
              {a.name}
            </option>
          ))}
        </select>
      </Field>
      <div className="flex gap-2 pt-2">
        <Button type="submit" disabled={isSubmitting}>
          {isSubmitting ? "Creating…" : "Create contact"}
        </Button>
        <Button type="button" variant="ghost" onClick={() => router.back()}>
          Cancel
        </Button>
      </div>
    </form>
  );
}
