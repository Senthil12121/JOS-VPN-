# JOS VPN India - Android WireGuard client

Kotlin Android app (Android 13+, minSdk 33) built on the WireGuard tunnel library.
Import a WireGuard `.conf` profile, connect or disconnect, and optionally route only
selected apps through the VPN.

No VPN servers are included. The profile you import decides the server and country.

## Build the APK with GitHub Actions

1. Push this project to a GitHub repository.
2. Open the **Actions** tab and run **Build JOS VPN India APK** (it also runs on every push to `main`).
3. Download the `JOSVPN-India-debug-apk` artifact and extract `app-debug.apk`.
4. Copy the APK to your phone and install it.

## Use

1. Tap **Import WireGuard .conf** and pick a profile from a provider you trust or your own server.
2. Optionally tap **Choose apps** to send only those apps through the VPN (none selected = all apps).
3. Tap **Connect VPN** and approve Android's VPN permission.

## Notes

- Keep `.conf` files private: they contain your private key. The `.gitignore` excludes them.
- The imported profile is stored in the app's private storage, not encrypted.
- App selection is split tunnelling, not a firewall.
- `app/build.gradle.kts` runs the debug build through R8 (see the comment there) to work around
  an Android Gradle Plugin dexing error caused by Java records in the WireGuard library.
