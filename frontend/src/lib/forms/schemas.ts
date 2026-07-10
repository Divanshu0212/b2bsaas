import { z } from "zod";

/*
 * Client-side validation mirroring the backend Bean Validation rules (T6.9), so invalid
 * input is blocked with the SAME rules the server enforces — no round-trip for the obvious
 * cases. Keep these in sync with the *Request records. Empty optional strings normalize to
 * null so the backend sees a real absence, not "".
 */

const optionalString = z
  .string()
  .trim()
  .transform((s) => (s === "" ? null : s))
  .nullable()
  .optional()
  .transform((s) => s ?? null);

const optionalUuid = z
  .string()
  .trim()
  .transform((s) => (s === "" ? null : s))
  .nullable()
  .optional()
  .transform((s) => s ?? null)
  .refine((v) => v === null || z.string().uuid().safeParse(v).success, "Must be a valid id");

export const leadStatusEnum = z.enum([
  "NEW",
  "CONTACTED",
  "QUALIFIED",
  "UNQUALIFIED",
  "CONVERTED",
]);

export const leadFormSchema = z.object({
  status: leadStatusEnum, // @NotNull
  source: optionalString,
  rawNotes: optionalString,
  contactId: optionalUuid,
  accountId: optionalUuid,
  ownerId: optionalUuid,
});
export type LeadFormValues = z.input<typeof leadFormSchema>;

export const accountFormSchema = z.object({
  name: z.string().trim().min(1, "Name is required"), // @NotBlank
  industry: optionalString,
  employeeCount: z
    .union([z.coerce.number().int().positive(), z.literal("")])
    .optional()
    .transform((v) => (v === "" || v === undefined ? null : v)),
  website: optionalString,
});
export type AccountFormValues = z.input<typeof accountFormSchema>;

export const contactFormSchema = z.object({
  accountId: optionalUuid,
  firstName: optionalString,
  lastName: optionalString,
  email: z
    .string()
    .trim()
    .nullable()
    .optional()
    .transform((s) => (s === "" || s == null ? null : s))
    .refine((v) => v === null || z.string().email().safeParse(v).success, "Invalid email"),
  phone: optionalString,
  title: optionalString,
});
export type ContactFormValues = z.input<typeof contactFormSchema>;

export const dealFormSchema = z.object({
  stageId: z.string().uuid("Pick a stage"), // @NotNull
  leadId: optionalUuid,
  accountId: optionalUuid,
  ownerId: optionalUuid,
  amount: z
    .union([z.coerce.number().nonnegative(), z.literal("")])
    .optional()
    .transform((v) => (v === "" || v === undefined ? null : v)),
  currency: optionalString,
  expectedCloseDate: optionalString,
});
export type DealFormValues = z.input<typeof dealFormSchema>;
