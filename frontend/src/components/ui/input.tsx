import { forwardRef, type InputHTMLAttributes } from "react";
import { cn } from "@/lib/cn";

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(
  function Input({ className, ...props }, ref) {
    return (
      <input
        ref={ref}
        className={cn(
          "h-10 w-full rounded-none border-0 border-b border-hairline bg-transparent px-0.5 text-sm text-ink",
          "placeholder:text-faint",
          "transition-colors focus-visible:border-accent focus-visible:outline-none",
          "disabled:opacity-50",
          className,
        )}
        {...props}
      />
    );
  },
);
