# Changelog

## 0.3.0

- Wider compatibility: the plugin no longer requires a specific Flutter,
  Kotlin or Android Gradle Plugin version. Minimum supported: Flutter 3.24 /
  Dart 3.5 (was 3.47.9 / 3.13.7), Android `minSdk` 21 (was the host Flutter
  default), AGP 8.1 and Kotlin 1.8.22 in the host app, iOS 12.0. Tested with
  Flutter 3.24.5 and 3.50.1.
- Android: no explicit `kotlin-stdlib` dependency, so the host's Kotlin
  version is used. With AGP 9 built-in Kotlin the plugin does not apply the
  Kotlin Gradle Plugin; with AGP 8 it does. Java/Kotlin bytecode target 17.
- Android `compileSdk` follows the host's Flutter (34 on Flutter 3.24).
- Removed the unused `plugin_platform_interface` dependency.
- Kotlin Multiplatform `core-kmp`: built with Kotlin 2.4.21 and AGP 9.5.2,
  published with language/API version 2.3. Android/JVM consumers need
  Kotlin 2.3+, iOS consumers Kotlin 2.4+. `kotlinx-serialization-json` 1.13.0,
  `compileSdk` 37.
- No API or behavior change.

## 0.2.1

- A new match token (a Universal/App Link received while the app runs, or a
  token passed to `setDeferredLinkToken`) now allows exactly one new match
  even after a non-organic match, and its deep link and attribution are
  delivered. Before, only an organic install could match again.
- The same token never matches twice, also across app restarts.
- A new token that gets no match (`404`) keeps the earlier attribution.
- Apps that never receive a new token keep the first-open result as before.
- Same rules in the Kotlin Multiplatform `AdTechClient.matchDevice`.

## 0.2.0

- `track` accepts optional `eventId`, `valueCents` and `currency` (sent as
  `event_id`, `value_cents`, `currency`), validated like the API.
- The match no longer collects or sends device signals (model, screen,
  storage, locale, time zone). Matching is by opaque click token only.
- Licensed under Apache-2.0.

## 0.1.0

- First release: `init`, first-open match, `setDeferredLinkUrl`,
  `setDeferredLinkToken`, Play Install Referrer, App Clip App Group handoff,
  `track`.
