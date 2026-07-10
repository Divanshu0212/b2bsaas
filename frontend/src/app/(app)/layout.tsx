import type { ReactNode } from "react";
import { AppShell } from "@/components/nav/AppShell";

/** Route group for authenticated app pages — wraps them in the sidebar/topbar shell. */
export default function AppLayout({ children }: { children: ReactNode }) {
  return <AppShell>{children}</AppShell>;
}
