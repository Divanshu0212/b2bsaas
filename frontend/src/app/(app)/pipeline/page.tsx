import Link from "next/link";
import { Board } from "@/components/kanban/Board";
import { Button } from "@/components/ui/button";

export default function PipelinePage() {
  return (
    <div>
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-xl font-semibold tracking-tight">Pipeline</h1>
        <Link href="/deals/new">
          <Button size="sm">New deal</Button>
        </Link>
      </div>
      <Board />
    </div>
  );
}
