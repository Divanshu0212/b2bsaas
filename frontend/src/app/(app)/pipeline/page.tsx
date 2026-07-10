import Link from "next/link";
import { Board } from "@/components/kanban/Board";
import { Button } from "@/components/ui/button";
import { PageHeader } from "@/components/ui/page-header";

export default function PipelinePage() {
  return (
    <div>
      <PageHeader
        eyebrow="Deals in motion"
        title="Pipeline"
        action={
          <Link href="/deals/new">
            <Button size="sm">New deal</Button>
          </Link>
        }
      />
      <Board />
    </div>
  );
}
