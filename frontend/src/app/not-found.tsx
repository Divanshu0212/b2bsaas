import Link from "next/link";

export default function NotFound() {
  return (
    <div className="mx-auto max-w-md py-24 text-center">
      <p className="text-sm font-medium text-accent">404</p>
      <h1 className="mt-1 text-lg font-semibold">Page not found</h1>
      <p className="mt-2 text-sm text-muted">That page doesn&apos;t exist.</p>
      <Link href="/pipeline" className="mt-4 inline-block text-sm text-accent hover:underline">
        Back to pipeline
      </Link>
    </div>
  );
}
