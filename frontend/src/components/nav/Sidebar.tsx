"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { KanbanSquare, Users, BarChart3, Bell, Building2, Contact, AlertTriangle } from "lucide-react";
import { cn } from "@/lib/cn";
import type { Role } from "@/lib/api/schema";

interface NavItem {
  href: string;
  label: string;
  icon: typeof KanbanSquare;
  /** Roles allowed to see this item; undefined = everyone. */
  roles?: Role[];
}

const ITEMS: NavItem[] = [
  { href: "/pipeline", label: "Pipeline", icon: KanbanSquare },
  { href: "/leads", label: "Leads", icon: Users },
  { href: "/accounts", label: "Accounts", icon: Building2 },
  { href: "/contacts", label: "Contacts", icon: Contact },
  // Reports + DLQ are admin surfaces — hidden from SALES_REP (T6.4 RBAC).
  { href: "/reports", label: "Reports", icon: BarChart3, roles: ["ADMIN"] },
  { href: "/notifications", label: "Notifications", icon: Bell },
  { href: "/admin/dlq", label: "Dead letters", icon: AlertTriangle, roles: ["ADMIN"] },
];

export function Sidebar({ role }: { role: Role | null }) {
  const pathname = usePathname();

  return (
    <nav className="flex w-56 shrink-0 flex-col border-r border-hairline bg-surface px-3 py-4">
      <Link href="/pipeline" className="mb-6 flex items-center gap-2 px-2">
        <span className="inline-block h-5 w-1.5 rounded-full bg-accent" aria-hidden />
        <span className="text-base font-semibold tracking-tight">SalesPipe</span>
      </Link>

      <ul className="space-y-0.5">
        {ITEMS.filter((it) => !it.roles || (role && it.roles.includes(role))).map((it) => {
          const active = pathname === it.href || pathname.startsWith(it.href + "/");
          const Icon = it.icon;
          return (
            <li key={it.href}>
              <Link
                href={it.href}
                aria-current={active ? "page" : undefined}
                className={cn(
                  "flex items-center gap-3 rounded-md px-3 py-2 text-sm transition-colors",
                  active
                    ? "bg-accent-soft text-accent font-medium"
                    : "text-muted hover:bg-hairline/50 hover:text-ink",
                )}
              >
                <Icon size={18} aria-hidden />
                {it.label}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
