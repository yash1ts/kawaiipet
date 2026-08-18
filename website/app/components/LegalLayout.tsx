import type { ReactNode } from "react";
import Link from "next/link";
import { AuroraBackground } from "@/components/ui/aurora-background";

type LegalLayoutProps = {
  title: string;
  updated: string;
  children: ReactNode;
};

export function LegalLayout({ title, updated, children }: LegalLayoutProps) {
  return (
    <AuroraBackground className="text-slate-800">
      <header className="relative z-10 border-b border-slate-200/40 bg-white/55 backdrop-blur-md">
        <div className="mx-auto flex max-w-3xl items-center justify-between px-4 py-4 sm:px-6">
          <Link
            href="/"
            className="text-sm font-semibold text-[var(--color-primary-deep)] transition-colors hover:text-slate-900"
          >
            ← KawaiiPet
          </Link>
          <nav className="flex items-center gap-5 text-sm font-medium text-slate-600">
            <Link href="/privacy" className="transition-colors hover:text-slate-900">
              Privacy
            </Link>
            <Link href="/terms" className="transition-colors hover:text-slate-900">
              Terms
            </Link>
          </nav>
        </div>
      </header>

      <article className="relative z-10 mx-auto max-w-3xl px-4 py-12 sm:px-6 sm:py-16">
        <h1 className="text-3xl font-bold tracking-tight text-slate-900 sm:text-4xl">{title}</h1>
        <p className="mt-2 text-sm text-slate-500">Last updated: {updated}</p>
        <div className="prose-custom mt-10 space-y-8 text-slate-700">{children}</div>
      </article>
    </AuroraBackground>
  );
}

export function LegalLink({ href, children }: { href: string; children: ReactNode }) {
  const external = href.startsWith("http");
  return (
    <a
      className="text-[var(--color-primary)] underline-offset-2 hover:underline"
      href={href}
      {...(external ? { rel: "noopener noreferrer", target: "_blank" } : {})}
    >
      {children}
    </a>
  );
}
