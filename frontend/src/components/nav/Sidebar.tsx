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
    <nav className="flex w-60 shrink-0 flex-col border-r border-hairline bg-surface px-5 py-6">
      <Link href="/pipeline" className="mb-8 flex items-baseline gap-2 px-1">
        <span className="display text-xl font-semibold tracking-tight text-ink">
          SalesPipe
        </span>
        <span className="inline-block h-1.5 w-1.5 translate-y-[-0.15em] bg-accent" aria-hidden />
      </Link>

      {/* Nav as ledger line-items: active is marked by ink weight + a filled tick, not a pill. */}
      <ul className="space-y-px">
        {ITEMS.filter((it) => !it.roles || (role && it.roles.includes(role))).map((it) => {
          const active = pathname === it.href || pathname.startsWith(it.href + "/");
          const Icon = it.icon;
          return (
            <li key={it.href}>
              <Link
                href={it.href}
                aria-current={active ? "page" : undefined}
                className={cn(
                  "group relative flex items-center gap-3 py-2 pl-4 pr-2 text-[13px] transition-colors",
                  active
                    ? "font-semibold text-ink"
                    : "font-medium text-muted hover:text-ink",
                )}
              >
                <span
                  className={cn(
                    "absolute left-0 top-1/2 h-4 w-0.5 -translate-y-1/2 transition-colors",
                    active ? "bg-accent" : "bg-transparent group-hover:bg-hairline",
                  )}
                  aria-hidden
                />
                <Icon
                  size={16}
                  strokeWidth={active ? 2.25 : 1.75}
                  className={active ? "text-accent" : "text-faint group-hover:text-muted"}
                  aria-hidden
                />
                {it.label}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
