import { existsSync } from "node:fs";
import path from "node:path";

const DEFAULT_APK_HREF =
  "https://pub-a620525dba554c409806691cd79a7010.r2.dev/app-release.apk";

/**
 * Resolves a working APK download URL.
 * Priority: APK_LINK → APP_LINK (alias) → NEXT_PUBLIC_APK_LINK → NEXT_PUBLIC_APK_URL → public/kawaiipet.apk → default R2 URL
 */
export function resolveApkDownloadHref(): string {
  const fromEnv =
    process.env.APK_LINK?.trim() ||
    process.env.NEXT_PUBLIC_APK_LINK?.trim() ||
    process.env.NEXT_PUBLIC_APK_URL?.trim();
  if (fromEnv) return fromEnv;
  const apkPath = path.join(process.cwd(), "public", "kawaiipet.apk");
  if (existsSync(apkPath)) return "/kawaiipet.apk";
  return DEFAULT_APK_HREF;
}
