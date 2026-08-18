import type { Metadata } from "next";
import Link from "next/link";
import { LegalLayout, LegalLink } from "../components/LegalLayout";
import { getOptionalContactEmail } from "../../lib/site";

export const metadata: Metadata = {
  title: "Privacy Policy | KawaiiPet",
  description:
    "How KawaiiPet handles your chats, memories, and this website. Conversations run on your device.",
};

const UPDATED = "August 18, 2026";

export default function PrivacyPage() {
  const contactEmail = getOptionalContactEmail();

  return (
    <LegalLayout title="Privacy Policy" updated={UPDATED}>
      <section className="space-y-3">
        <h2 className="text-xl font-semibold text-slate-900">The short version</h2>
        <p className="leading-relaxed">
          KawaiiPet is built so you can talk to a pet that lives on your phone. Voice, chat, and memories
          are processed on your device. We do not send your conversations to a cloud AI to generate replies,
          and we do not sell your personal information.
        </p>
        <p className="leading-relaxed">
          The App still uses the internet to download models the first time you set up. This website
          (including the waitlist) is separate from the pet on your phone and is described below.
        </p>
      </section>

      <section className="space-y-3">
        <h2 className="text-xl font-semibold text-slate-900">Who this covers</h2>
        <p className="leading-relaxed">
          This policy describes how the KawaiiPet Android app (“App”) and this marketing website (“Site”)
          handle information. You do not need an account to use the App.
        </p>
        {contactEmail ? (
          <p className="leading-relaxed">
            For privacy questions, contact us at{" "}
            <LegalLink href={`mailto:${contactEmail}`}>{contactEmail}</LegalLink>.
          </p>
        ) : (
          <p className="leading-relaxed">
            For privacy questions, please reach out through the contact options provided in the App, if
            available.
          </p>
        )}
      </section>

      <section className="space-y-3">
        <h2 className="text-xl font-semibold text-slate-900">What stays on your device</h2>
        <p className="leading-relaxed">
          The companion itself runs locally. That includes:
        </p>
        <ul className="list-disc space-y-2 pl-5 leading-relaxed">
          <li>Microphone audio used to listen when you tap the pet, transcribed on-device</li>
          <li>Your spoken and typed chats, and the pet’s replies (generated on-device)</li>
          <li>Memories and preferences (name, personality, voice, reminders you set)</li>
          <li>
            App-usage information used for break reminders, processed on the phone so the pet can notice
            when you have been in a watched app too long
          </li>
        </ul>
        <p className="leading-relaxed">
          Uninstalling the App or clearing its data erases this local information. We cannot recover it.
        </p>
      </section>

      <section className="space-y-3">
        <h2 className="text-xl font-semibold text-slate-900">Permissions</h2>
        <p className="leading-relaxed">
          Depending on what you enable, the App may ask for:
        </p>
        <ul className="list-disc space-y-2 pl-5 leading-relaxed">
          <li>
            <span className="font-medium text-slate-800">Microphone</span> — so the pet can hear you after
            you tap it
          </li>
          <li>
            <span className="font-medium text-slate-800">Display over other apps</span> — so the pet can
            stay on screen
          </li>
          <li>
            <span className="font-medium text-slate-800">Usage access</span> — only if you turn on break
            reminders, so it can tell how long you have been in an app you chose to watch
          </li>
          <li>
            <span className="font-medium text-slate-800">Notifications</span> — for the overlay service and
            reminder monitor
          </li>
          <li>
            <span className="font-medium text-slate-800">Internet</span> — for first-time model downloads,
            not for sending your chat to a cloud model
          </li>
        </ul>
        <p className="leading-relaxed">
          You can refuse or later revoke permissions in Android settings. Some features will not work
          without them.
        </p>
      </section>

      <section className="space-y-3">
        <h2 className="text-xl font-semibold text-slate-900">This website and the waitlist</h2>
        <p className="leading-relaxed">
          If you join the waitlist, we collect the email address you submit so we can send product updates.
          Submissions are processed through Google Forms on our behalf. Google’s privacy documentation is
          at{" "}
          <LegalLink href="https://policies.google.com/privacy">policies.google.com/privacy</LegalLink>.
        </p>
        <p className="leading-relaxed">
          The Site is hosted by our website provider. Hosting logs may include standard technical data such
          as IP address, browser type, and pages requested. The Site may also use ordinary web analytics or
          hosting metrics (for example page views) so we can tell whether the website is working. The
          Android APK, when offered, may be served from object storage (for example Cloudflare R2).
        </p>
        <p className="leading-relaxed">
          The App itself does not include product analytics. Talking to your pet does not send usage
          events about your chats.
        </p>
      </section>

      <section className="space-y-3">
        <h2 className="text-xl font-semibold text-slate-900">Model and app downloads</h2>
        <p className="leading-relaxed">
          The first time you set up the App, it downloads on-device speech and language models from third
          parties (such as GitHub and Hugging Face) so listening, talking, and memory search can run
          offline afterward. Those downloads are model files, not your chats.
        </p>
      </section>

      <section className="space-y-3">
        <h2 className="text-xl font-semibold text-slate-900">What we do not do</h2>
        <ul className="list-disc space-y-2 pl-5 leading-relaxed">
          <li>We do not sell your personal information.</li>
          <li>
            We do not use your conversations to train a cloud model, and we do not send them to a hosted
            assistant API to generate the pet’s replies.
          </li>
          <li>We do not require an account to chat with your pet.</li>
          <li>We do not run product analytics inside the App.</li>
        </ul>
      </section>

      <section className="space-y-3">
        <h2 className="text-xl font-semibold text-slate-900">Retention and your choices</h2>
        <p className="leading-relaxed">
          On-device data stays on your phone until you delete it (or uninstall the App). Waitlist emails
          are kept so we can message you about KawaiiPet; you can ask us to remove yours. Website hosting
          and any Site analytics retain technical data according to those providers’ settings.
        </p>
      </section>

      <section className="space-y-3">
        <h2 className="text-xl font-semibold text-slate-900">Children</h2>
        <p className="leading-relaxed">
          KawaiiPet is not directed at children under 13 (or the minimum age required in your region). If
          you believe we have collected information from a child inappropriately, please contact us so we
          can take appropriate steps.
        </p>
      </section>

      <section className="space-y-3">
        <h2 className="text-xl font-semibold text-slate-900">Changes</h2>
        <p className="leading-relaxed">
          We may update this policy from time to time. We will post the updated version on this page and
          revise the “Last updated” date. Continued use of the App or Site after changes means you accept
          the updated policy.
        </p>
      </section>

      <p className="border-t border-slate-200 pt-8 text-sm text-slate-500">
        Related:{" "}
        <Link href="/terms" className="text-slate-600 underline-offset-2 hover:underline">
          Terms of Use
        </Link>
        . This policy is provided for transparency and is not legal advice.
      </p>
    </LegalLayout>
  );
}
