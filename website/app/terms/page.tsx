import type { Metadata } from "next";
import Link from "next/link";
import { LegalLayout, LegalLink } from "../components/LegalLayout";
import { getOptionalContactEmail } from "../../lib/site";

export const metadata: Metadata = {
  title: "Terms of Use | KawaiiPet",
  description:
    "Terms for using the KawaiiPet Android app and this website — including the on-device pet, reminders, and APK downloads.",
};

const UPDATED = "August 18, 2026";

export default function TermsPage() {
  const contactEmail = getOptionalContactEmail();

  return (
    <LegalLayout title="Terms of Use" updated={UPDATED}>
      <section className="space-y-3">
        <h2 className="text-xl font-semibold text-slate-900">Agreement</h2>
        <p className="leading-relaxed">
          These Terms of Use (“Terms”) govern your use of the KawaiiPet Android application (“App”) and
          this marketing website (“Site”). By downloading, installing, or using the App, joining the
          waitlist, or using the Site, you agree to these Terms.
        </p>
        <p className="leading-relaxed">
          If you do not agree, do not use the App or Site. Our{" "}
          <Link href="/privacy" className="text-[var(--color-primary)] underline-offset-2 hover:underline">
            Privacy Policy
          </Link>{" "}
          explains how information is handled and is part of how we operate the product.
        </p>
      </section>

      <section className="space-y-3">
        <h2 className="text-xl font-semibold text-slate-900">What KawaiiPet is</h2>
        <p className="leading-relaxed">
          KawaiiPet is a floating on-screen companion. You can tap the pet to talk, keep memories on your
          device, set break reminders for apps that eat your time, and ask it to help you stay on a
          work-and-break rhythm. Chat, speech, and memory run on your phone. The App is provided for
          personal, non-commercial use.
        </p>
      </section>

      <section className="space-y-3">
        <h2 className="text-xl font-semibold text-slate-900">Eligibility</h2>
        <p className="leading-relaxed">
          You must be old enough to use the App under the laws of your region (at least 13, or the higher
          age required where you live). If you use KawaiiPet on a device you do not own, you are
          responsible for having permission to install it and grant the permissions it needs.
        </p>
      </section>

      <section className="space-y-3">
        <h2 className="text-xl font-semibold text-slate-900">License</h2>
        <p className="leading-relaxed">
          We grant you a personal, limited, revocable, non-exclusive, non-transferable license to install
          and use the App on Android devices you control, for your own use, in line with these Terms. You
          may not redistribute the App as if it were your product, reverse engineer it except where the
          law allows, or use it to harm others or break the law.
        </p>
      </section>

      <section className="space-y-3">
        <h2 className="text-xl font-semibold text-slate-900">Your responsibilities</h2>
        <p className="leading-relaxed">You are responsible for:</p>
        <ul className="list-disc space-y-2 pl-5 leading-relaxed">
          <li>
            The permissions you grant (microphone, overlay, usage access, notifications) and how you use
            the pet on top of other apps
          </li>
          <li>
            What you say to the pet. Conversations stay on your device; still, do not share information
            you would not want stored on that phone
          </li>
          <li>
            Keeping the device reasonably secure, and understanding that uninstalling the App deletes local
            chats and memories
          </li>
          <li>
            Complying with third-party app policies if the pet opens or interacts with other apps at your
            request
          </li>
        </ul>
      </section>

      <section className="space-y-3">
        <h2 className="text-xl font-semibold text-slate-900">Early software and APKs</h2>
        <p className="leading-relaxed">
          Direct APK downloads may be pre-store builds. Features can change, break, or be incomplete.
          Sideloading carries the usual device risks; install only from this Site or another source you
          trust. Store listings, when available, may lag behind the APK.
        </p>
      </section>

      <section className="space-y-3">
        <h2 className="text-xl font-semibold text-slate-900">Not professional advice</h2>
        <p className="leading-relaxed">
          The pet is a companion, not a doctor, therapist, lawyer, or crisis service. Reminders and
          pomodoro-style focus are helpers, not guarantees that you will change a habit or finish a task.
          If you are in distress, contact local emergency services or a qualified professional.
        </p>
      </section>

      <section className="space-y-3">
        <h2 className="text-xl font-semibold text-slate-900">Network use</h2>
        <p className="leading-relaxed">
          After models are installed, talking to the pet is designed to work offline. The App may still
          use a connection to download or update models. The Site and APK hosting require a connection,
          and the Site may use ordinary hosting or web analytics. Offline chat does not mean the App
          never uses the internet.
        </p>
      </section>

      <section className="space-y-3">
        <h2 className="text-xl font-semibold text-slate-900">Intellectual property</h2>
        <p className="leading-relaxed">
          The App, Site, name, artwork, and related materials are owned by KawaiiPet or its licensors.
          Third-party models, fonts, and libraries remain the property of their owners and are used under
          their licenses. You keep whatever rights you already have in what you say to the pet; we do not
          claim ownership of your conversations.
        </p>
      </section>

      <section className="space-y-3">
        <h2 className="text-xl font-semibold text-slate-900">Third parties</h2>
        <p className="leading-relaxed">
          Model hosts, website hosting, waitlist forms, APK storage, and any Site analytics are operated
          by others under their own terms. We are not responsible for those services, except as the law
          requires.
        </p>
      </section>

      <section className="space-y-3">
        <h2 className="text-xl font-semibold text-slate-900">Disclaimer</h2>
        <p className="leading-relaxed">
          The App and Site are provided “as is” and “as available,” without warranties of any kind,
          whether express or implied, including merchantability, fitness for a particular purpose, and
          non-infringement, to the fullest extent permitted by law. We do not warrant that the pet will
          always hear you correctly, remember accurately, or interrupt a scrolling session when you hoped
          it would.
        </p>
      </section>

      <section className="space-y-3">
        <h2 className="text-xl font-semibold text-slate-900">Limitation of liability</h2>
        <p className="leading-relaxed">
          To the fullest extent permitted by law, KawaiiPet and its contributors are not liable for
          indirect, incidental, special, consequential, or punitive damages, or for lost data, lost time,
          or device issues arising from your use of the App or Site. Some places do not allow certain
          limits; in those places, our liability is limited to the maximum extent allowed.
        </p>
      </section>

      <section className="space-y-3">
        <h2 className="text-xl font-semibold text-slate-900">Termination</h2>
        <p className="leading-relaxed">
          You may stop using the App at any time by uninstalling it. We may stop offering the App, the
          Site, or a particular APK without notice. Provisions that should survive (including disclaimers
          and liability limits) will survive.
        </p>
      </section>

      <section className="space-y-3">
        <h2 className="text-xl font-semibold text-slate-900">Changes</h2>
        <p className="leading-relaxed">
          We may update these Terms. The “Last updated” date on this page will change when we do.
          Continued use after an update means you accept the new Terms. If you do not, uninstall the App
          and stop using the Site.
        </p>
      </section>

      <section className="space-y-3">
        <h2 className="text-xl font-semibold text-slate-900">Contact</h2>
        {contactEmail ? (
          <p className="leading-relaxed">
            Questions about these Terms:{" "}
            <LegalLink href={`mailto:${contactEmail}`}>{contactEmail}</LegalLink>.
          </p>
        ) : (
          <p className="leading-relaxed">
            Questions about these Terms can be sent through the contact options provided in the App, if
            available.
          </p>
        )}
      </section>

      <p className="border-t border-slate-200 pt-8 text-sm text-slate-500">
        Related:{" "}
        <Link href="/privacy" className="text-slate-600 underline-offset-2 hover:underline">
          Privacy Policy
        </Link>
        . These Terms are provided for clarity and are not legal advice.
      </p>
    </LegalLayout>
  );
}
