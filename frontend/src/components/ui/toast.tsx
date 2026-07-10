"use client";

import { createContext, useCallback, useContext, useState, type ReactNode } from "react";
import { cn } from "@/lib/cn";

type ToastKind = "info" | "success" | "error";
type Toast = { id: number; message: string; kind: ToastKind };

type ToastApi = {
  toast: (message: string, kind?: ToastKind) => void;
};

const ToastContext = createContext<ToastApi | null>(null);

/** Access the toast dispatcher. Must be inside <ToastProvider>. */
export function useToast(): ToastApi {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error("useToast must be used within ToastProvider");
  return ctx;
}

let nextId = 1;

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);

  const toast = useCallback((message: string, kind: ToastKind = "info") => {
    const id = nextId++;
    setToasts((t) => [...t, { id, message, kind }]);
    // Auto-dismiss; conflict toasts stay long enough to read the specifics.
    window.setTimeout(() => {
      setToasts((t) => t.filter((x) => x.id !== id));
    }, 5000);
  }, []);

  return (
    <ToastContext.Provider value={{ toast }}>
      {children}
      <div
        className="fixed bottom-4 right-4 z-50 flex flex-col gap-2"
        role="status"
        aria-live="polite"
      >
        {toasts.map((t) => (
          <div
            key={t.id}
            className={cn(
              "rounded-md border px-4 py-3 text-sm shadow-lg bg-surface max-w-sm",
              t.kind === "error" && "border-danger/30 text-danger",
              t.kind === "success" && "border-won/30 text-won",
              t.kind === "info" && "border-hairline text-ink",
            )}
          >
            {t.message}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}
