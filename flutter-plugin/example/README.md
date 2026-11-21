# Tixlnk Flutter SDK example

Runnable Android and iOS example for the Tixlnk Flutter plugin.

## Run on Android Emulator

From this directory, run `fvm flutter run`. The example defaults to
`http://10.0.2.2:58080`, which reaches a backend running on the host machine.

Register the example app in the Tixlnk dashboard and enter its one-time
`pk_live_...` API key. On Android, Init reads the Play install referrer before
the first match. Leave the optional link field empty for that path. Pasting a
short-link URL is only for an installed-app App Link or a manual retry. On
iOS, the example listens for initial and warm Universal Links through
`app_links`; configure an active Associated Domain for your link domain. The app includes a non-deferred routing simulation and purchase-event
action. An emulator without a Play install does not prove the referrer.

Run widget tests and static analysis with `fvm flutter test` and
`fvm flutter analyze`.
