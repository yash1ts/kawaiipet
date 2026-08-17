"use client";

import { AuroraBackground } from "@/components/ui/aurora-background";
import { StoreDownloadButtons } from "./StoreDownloadButtons";
import { WaitlistForm } from "./WaitlistForm";
import Image from "next/image";
import Link from "next/link";
import { motion, useReducedMotion } from "framer-motion";
import { BookHeart, Heart, Mic, ShieldCheck, Smartphone, Timer } from "lucide-react";

type LandingPageProps = {
  apkHref: string | null;
  githubUrl: string | null;
};

const LOGO_PATH = "/transparent smile (1).png";

const fadeUp = {
  hidden: { opacity: 0, y: 24 },
  show: (i: number) => ({
    opacity: 1,
    y: 0,
    transition: { delay: i * 0.08, duration: 0.55, ease: [0.22, 1, 0.36, 1] as const },
  }),
};

const steps = [
  {
    n: "01",
    title: "Tap the pet",
    body: "It’s already on your screen. One tap, and it starts listening — no app to open, no keyboard required.",
  },
  {
    n: "02",
    title: "Just speak",
    body: "Talk the way you would to a friend. Vent, share a win, ask for a focus session, or simply say hi.",
  },
  {
    n: "03",
    title: "It talks back",
    body: "Your pet answers out loud, remembers what you told it, and stays with you while you keep using your phone.",
  },
];

const features: {
  title: string;
  body: string;
  icon: typeof Heart;
  featured?: boolean;
}[] = [
  {
    title: "A friend you can talk to",
    body: "Chat out loud anytime. Your pet hangs out with you — curious, warm, and on your side. Not a chatbot. A companion.",
    icon: Heart,
  },
  {
    title: "It remembers you",
    body: "Names, stories, the little things. Memories stay with your pet so the next conversation picks up where you left off — not from zero.",
    icon: BookHeart,
  },
  {
    title: "A gentle tap when time slips",
    body: "Doom-scrolling? Lost in another app? Set a reminder on the apps that eat your time. When you’ve been in too long, your pet pops up — not to scold, just to say hey.",
    icon: Smartphone,
  },
  {
    title: "Ask it to keep you focused",
    body: "Need a pomodoro? Tell your pet. It’ll hold a work-and-break rhythm with you so tasks actually get done — with a friend in the corner of the screen.",
    icon: Timer,
  },
  {
    title: "Completely offline. Completely yours.",
    body: "Voice, chat, and memories run on your phone — not in the cloud. No signal, airplane mode, late night. Your conversations stay private.",
    icon: ShieldCheck,
    featured: true,
  },
];

export function LandingPage({ apkHref, githubUrl }: LandingPageProps) {
  const reduceMotion = useReducedMotion();

  return (
    <AuroraBackground className="text-slate-900">
      <header className="relative z-10 border-b border-slate-200/40 bg-white/55 backdrop-blur-md">
        <div className="mx-auto flex max-w-5xl items-center justify-between gap-4 px-4 py-4 sm:px-6">
          <Link
            href="/"
            className="flex items-center gap-3 rounded-lg outline-offset-4 focus-visible:outline focus-visible:outline-2 focus-visible:outline-[var(--color-primary)]"
          >
            <Image
              src={LOGO_PATH}
              alt=""
              width={44}
              height={44}
              className="h-11 w-11 object-contain drop-shadow-[0_1px_8px_rgba(15,23,42,0.08)]"
              priority
            />
            <span className="text-lg font-semibold tracking-tight text-slate-900">KawaiiPet</span>
          </Link>
          <nav className="flex items-center gap-5 text-sm font-medium text-slate-600 sm:gap-6">
            <a href="#how" className="hidden transition-colors hover:text-slate-900 sm:inline">
              How it works
            </a>
            <a href="#features" className="transition-colors hover:text-slate-900">
              Features
            </a>
            <a href="#download" className="transition-colors hover:text-slate-900">
              Download
            </a>
          </nav>
        </div>
      </header>

      <main className="relative z-10 flex flex-1 flex-col">
        <section className="mx-auto flex max-w-5xl flex-col items-center px-4 pb-20 pt-16 text-center sm:px-6 sm:pb-24 sm:pt-20">
          <motion.div
            initial={reduceMotion ? false : { opacity: 0, scale: 0.92, y: 12 }}
            animate={reduceMotion ? undefined : { opacity: 1, scale: 1, y: 0 }}
            transition={{ duration: 0.7, ease: [0.22, 1, 0.36, 1] }}
            className="mb-10"
          >
            <div className="relative">
              <div className="absolute inset-0 -m-10 rounded-full bg-violet-50/35 blur-3xl" />
              <Image
                src={LOGO_PATH}
                alt="KawaiiPet"
                width={160}
                height={160}
                className="relative h-36 w-36 object-contain drop-shadow-[0_4px_20px_rgba(15,23,42,0.06)] sm:h-40 sm:w-40"
                priority
              />
              <span className="absolute -bottom-3 left-1/2 inline-flex -translate-x-1/2 items-center gap-1.5 whitespace-nowrap rounded-full border border-violet-200/80 bg-white/90 px-3 py-1 text-xs font-semibold tracking-tight text-violet-700 shadow-sm shadow-violet-200/50 backdrop-blur-sm">
                <Mic className="h-3.5 w-3.5" aria-hidden strokeWidth={2.25} />
                Tap to talk
              </span>
            </div>
          </motion.div>

          <motion.h1
            custom={0}
            variants={fadeUp}
            initial="hidden"
            animate="show"
            className="mt-4 max-w-2xl text-4xl font-bold tracking-tight text-slate-900 sm:text-5xl sm:leading-[1.1]"
          >
            A tiny friend who lives on your screen
          </motion.h1>
          <motion.p
            custom={1}
            variants={fadeUp}
            initial="hidden"
            animate="show"
            className="mt-5 max-w-xl text-lg leading-relaxed text-slate-600"
          >
            Tap to talk. Your pet chats with you, remembers what matters, gently pulls you back from
            endless scrolling, and keeps you company while you get things done — fully offline, so
            your chats stay private.
          </motion.p>

          <WaitlistForm motionIndex={2} />

          <motion.div
            id="download"
            custom={3}
            variants={fadeUp}
            initial="hidden"
            animate="show"
            className="scroll-mt-24 mt-10 w-full"
          >
            <StoreDownloadButtons apkHref={apkHref} />
          </motion.div>
        </section>

        <section id="how" className="relative scroll-mt-24 border-t border-slate-200/50 py-20">
          <div className="mx-auto max-w-5xl px-4 sm:px-6">
            <p className="mb-3 text-center text-sm font-semibold tracking-wide text-violet-600 uppercase">
              Talking to your pet
            </p>
            <h2 className="mb-12 text-center text-2xl font-semibold tracking-tight text-slate-900 sm:text-3xl">
              Just tap. Then speak.
            </h2>
            <ol className="grid gap-6 sm:grid-cols-3">
              {steps.map((step, i) => (
                <motion.li
                  key={step.n}
                  initial={reduceMotion ? false : { opacity: 0, y: 20 }}
                  whileInView={reduceMotion ? undefined : { opacity: 1, y: 0 }}
                  viewport={{ once: true, margin: "-40px" }}
                  transition={{ delay: i * 0.1, duration: 0.5, ease: [0.22, 1, 0.36, 1] }}
                  className="rounded-2xl border border-slate-200/60 bg-white/75 p-6 shadow-sm shadow-slate-200/40 backdrop-blur-sm"
                >
                  <span className="text-sm font-semibold tracking-widest text-violet-500">{step.n}</span>
                  <h3 className="mt-3 text-lg font-semibold text-slate-900">{step.title}</h3>
                  <p className="mt-2 text-sm leading-relaxed text-slate-600">{step.body}</p>
                </motion.li>
              ))}
            </ol>
          </div>
        </section>

        <section id="features" className="relative scroll-mt-24 border-t border-slate-200/50 py-20">
          <div className="mx-auto max-w-5xl px-4 sm:px-6">
            <p className="mb-3 text-center text-sm font-semibold tracking-wide text-violet-600 uppercase">
              What it can do
            </p>
            <h2 className="mb-4 text-center text-2xl font-semibold tracking-tight text-slate-900 sm:text-3xl">
              Company first. Help when you ask.
            </h2>
            <p className="mx-auto mb-12 max-w-lg text-center text-base leading-relaxed text-slate-600">
              Hang out with a pet that knows you — and, when you want it, a little extra help to take
              back your time.
            </p>
            <ul className="grid gap-6 sm:grid-cols-2">
              {features.map((f, i) => {
                const Icon = f.icon;
                return (
                  <motion.li
                    key={f.title}
                    initial={reduceMotion ? false : { opacity: 0, y: 20 }}
                    whileInView={reduceMotion ? undefined : { opacity: 1, y: 0 }}
                    viewport={{ once: true, margin: "-40px" }}
                    transition={{ delay: i * 0.08, duration: 0.5, ease: [0.22, 1, 0.36, 1] }}
                    className={
                      f.featured
                        ? "rounded-2xl border border-violet-200/70 bg-white/80 p-6 shadow-sm shadow-violet-200/40 backdrop-blur-sm sm:col-span-2 sm:p-7"
                        : "rounded-2xl border border-slate-200/60 bg-white/75 p-6 shadow-sm shadow-slate-200/40 backdrop-blur-sm sm:p-7"
                    }
                  >
                    <div className="mb-4 inline-flex rounded-xl bg-slate-50 p-3 text-slate-700 ring-1 ring-slate-200/80">
                      <Icon className="h-6 w-6" aria-hidden strokeWidth={1.75} />
                    </div>
                    <h3 className="text-lg font-semibold text-slate-900">{f.title}</h3>
                    <p className="mt-2 text-sm leading-relaxed text-slate-600">{f.body}</p>
                  </motion.li>
                );
              })}
            </ul>
          </div>
        </section>
      </main>

      <footer className="relative z-10 border-t border-slate-200/40 bg-white/55 py-10 backdrop-blur-md">
        <div className="mx-auto flex max-w-5xl flex-col items-center justify-center gap-4 px-4 text-center text-sm text-slate-500 sm:flex-row sm:justify-between sm:px-6 sm:text-left">
          <p>© {new Date().getFullYear()} KawaiiPet</p>
          <div className="flex flex-wrap items-center justify-center gap-6">
            <Link href="/privacy" className="text-slate-600 transition-colors hover:text-slate-900">
              Privacy
            </Link>
            {githubUrl ? (
              <a
                href={githubUrl}
                className="text-slate-600 transition-colors hover:text-slate-900"
                rel="noopener noreferrer"
                target="_blank"
              >
                GitHub
              </a>
            ) : null}
          </div>
        </div>
      </footer>
    </AuroraBackground>
  );
}
