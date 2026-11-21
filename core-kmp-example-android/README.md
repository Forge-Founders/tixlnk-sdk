# KMP Example App (Android)

This example app shows how to consume `:core-kmp` directly from Android:

- Initialize `AdTechClient`
- Run `matchDevice()` for the deferred flow. The app reads the Play install referrer and does not require a pasted token.
- Send `track()` events

## Run

From repository root:

```bash
mise x -- gradle :core-kmp-example-android:assembleDebug
```

Then install the APK from:

- `core-kmp-example-android/build/outputs/apk/debug/core-kmp-example-android-debug.apk`

Use local endpoint:

- `http://10.0.2.2:58080` (Android emulator to the local API)
