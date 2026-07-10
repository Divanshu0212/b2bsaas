import Link from "next/link";

export default function NotFound() {
  return (
    <div className="mx-auto max-w-md py-24 text-center">
      <p className="display text-6xl font-semibold tracking-tight text-accent">404</p>
      <h1 className="display mt-2 text-2xl font-semibold tracking-tight text-ink">
        Page not found
      </h1>
      <p className="mt-2 text-sm text-muted">That page doesn&apos;t exist.</p>
      <Link
        href="/pipeline"
        className="mt-6 inline-block text-sm text-accent underline-offset-4 hover:underline"
      >
        Back to pipeline
      </Link>
    </div>
  );
}
