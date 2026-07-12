<wizard-report>
# PostHog post-wizard report

The wizard has completed a deep integration of PostHog analytics into KawaiiPet, an Android AI companion app built with Kotlin and Jetpack Compose. PostHog is initialized in the `Application` class using credentials read from `local.properties` via `BuildConfig`, following the established Supabase pattern already in the project.

Users are identified (via `PostHog.identify`) on both sign-in and sign-up, linking their Supabase user ID as the distinct ID and attaching their email as a person property. On sign-out, `PostHog.reset()` is called to clear the session. Ten custom events are tracked across six files covering the full user lifecycle: account creation, pet usage, AI conversations, and customization.

## Events Instrumented

| Event Name | Description | File |
|---|---|---|
| `user signed up` | User successfully created a new account via email/password | `app/src/main/java/com/kawaiipet/app/ui/auth/AuthEmailViewModel.kt` |
| `user signed in` | User successfully authenticated with email/password | `app/src/main/java/com/kawaiipet/app/ui/auth/AuthEmailViewModel.kt` |
| `user signed out` | User explicitly signed out of their account | `app/src/main/java/com/kawaiipet/app/ui/screens/SettingsScreen.kt` |
| `pet started` | User successfully launched the pet overlay (all permissions granted) | `app/src/main/java/com/kawaiipet/app/ui/screens/HomeScreen.kt` |
| `pet stopped` | Pet overlay service was destroyed (user dismissed or swiped away) | `app/src/main/java/com/kawaiipet/app/overlay/PetOverlayService.kt` |
| `voice conversation initiated` | User tapped the pet to start a voice conversation | `app/src/main/java/com/kawaiipet/app/pet/PetViewModel.kt` |
| `text conversation initiated` | User long-pressed the pet to open the text input overlay | `app/src/main/java/com/kawaiipet/app/pet/PetViewModel.kt` |
| `ai response received` | Pet AI responded to user input; includes expression and response length | `app/src/main/java/com/kawaiipet/app/pet/PetViewModel.kt` |
| `pet customized` | User saved pet customization settings (name, personality, voice, volume) | `app/src/main/java/com/kawaiipet/app/ui/screens/CustomizeScreen.kt` |
| `profile synced to cloud` | User pushed their pet profile to cloud storage via Supabase | `app/src/main/java/com/kawaiipet/app/ui/screens/CustomizeScreen.kt` |

## Files Modified

- `gradle/libs.versions.toml` — added `posthog = "3.+"` version and `posthog-android` library entry
- `app/build.gradle.kts` — added `posthog.apiKey`/`posthog.host` from `local.properties` as `BuildConfig` fields; added `implementation(libs.posthog.android)` dependency
- `local.properties` — added `posthog.apiKey` and `posthog.host` (gitignored)
- `app/src/main/AndroidManifest.xml` — added `android:label` to `MainActivity` for accurate screen tracking
- `app/src/main/java/com/kawaiipet/app/KawaiiPetApplication.kt` — PostHog SDK initialization via `PostHogAndroid.setup()`
- `app/src/main/java/com/kawaiipet/app/ui/auth/AuthEmailViewModel.kt` — `user signed up`, `user signed in`, and `PostHog.identify`
- `app/src/main/java/com/kawaiipet/app/ui/screens/SettingsScreen.kt` — `user signed out` and `PostHog.reset()`
- `app/src/main/java/com/kawaiipet/app/ui/screens/HomeScreen.kt` — `pet started`
- `app/src/main/java/com/kawaiipet/app/overlay/PetOverlayService.kt` — `pet stopped`
- `app/src/main/java/com/kawaiipet/app/pet/PetViewModel.kt` — `voice conversation initiated`, `text conversation initiated`, `ai response received`
- `app/src/main/java/com/kawaiipet/app/ui/screens/CustomizeScreen.kt` — `pet customized`, `profile synced to cloud`

## Next steps

We've built some insights and a dashboard for you to keep an eye on user behavior, based on the events we just instrumented:

- **Dashboard — Analytics basics**: https://us.posthog.com/project/369643/dashboard/1432034
- **User Sign-up to First Conversation Funnel**: https://us.posthog.com/project/369643/insights/IpZ2Ut9k
- **New Sign-ups vs Sign-ins (Daily)**: https://us.posthog.com/project/369643/insights/5ct1M7uV
- **Daily Active Pet Users**: https://us.posthog.com/project/369643/insights/7ZSEIv7x
- **Conversation Activity: Voice vs Text**: https://us.posthog.com/project/369643/insights/txHgW8xl
- **Pet Session Engagement Funnel**: https://us.posthog.com/project/369643/insights/yUgZvKhP

### Agent skill

We've left an agent skill folder in your project. You can use this context for further agent development when using Claude Code. This will help ensure the model provides the most up-to-date approaches for integrating PostHog.

</wizard-report>
