# Changelog

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
