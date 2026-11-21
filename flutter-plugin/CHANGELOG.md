# Changelog

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
