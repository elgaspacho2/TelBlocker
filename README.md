# TelBlocker

**Per-app telemetry blocker for Android, no root required.**

TelBlocker lets you choose, app by app, what gets to phone home. You can block only
the tracking and analytics traffic of an app, or cut an app off from the network
entirely. A single switch does that for Google Play Services and the Play Store.

Works on Android 10 (API 29) through Android 17.

## Features

- **Per-app modes.** Tap an app and choose one of three modes:
  - *Off*: the app is not touched.
  - *Telemetry*: requests to known tracking and analytics domains are blocked, and
    everything else works normally.
  - *Block everything*: the app has no network access at all, in or out.
- **One switch for Google services.** It puts Google Play Services, Google Services
  Framework and the Play Store in *Block everything* mode in one tap.
- **Live log.** It shows every DNS request and blocked connection, and which app made
  it. Tap an entry to block that domain, or to allow it if an app stops working.
- **Custom rules.** You can add your own blocked domains and exceptions. Subdomains
  are covered automatically.
- **Built-in blocklist.** It covers Firebase / Google Analytics, Crashlytics,
  AppsFlyer, Adjust, Sentry, Mixpanel, Amplitude, Microsoft, Unity, Xiaomi, Huawei
  and more.
- **Private by design.** Everything runs on the phone. There is no account, no
  server and no analytics of its own.

## How it works

TelBlocker uses Android's `VpnService` to create a **local** VPN. No traffic is sent
to any remote server. The VPN applies only to the apps you selected.

- **Telemetry mode.** The VPN only captures DNS. Requests for known telemetry domains
  get a "domain does not exist" answer. All other requests go to your normal DNS
  server, and the rest of the app's traffic doesn't go through TelBlocker at all.
- **Block everything mode.** Every DNS request from the app fails. If *all* filtered
  apps are in this mode, TelBlocker also captures and drops all of their traffic,
  including connections made directly to IP addresses.

## Install

1. Go to the [Actions](../../actions) tab, open the latest successful
   **TelemetryBlocker APK** run and download the `TelemetryBlocker-apk` artifact.
2. Unzip it and copy `app-debug.apk` to your phone.
3. Open the APK and allow installs from your browser or file manager when asked.
4. In the app, choose a mode for the apps you want, tap **Activer la protection**,
   and accept Android's VPN request.

### Build it yourself

Requirements: JDK 17 and the Android SDK.

```sh
cd TelemetryBlocker
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

## Limitations

- **One VPN at a time.** Android allows only one active VPN, so TelBlocker can't run
  alongside another VPN app.
- **Mixed modes weaken full blocking.** If some apps use *Telemetry* mode while
  others use *Block everything*, the blocked apps are cut off through DNS only. A
  connection to a hard-coded IP address, or one opened before you enabled the
  protection, could still get through. For a complete block, use only *Block
  everything*.
- **Private DNS.** If Android's Private DNS is set to a specific hostname, filtering
  can be bypassed. Set it to *Automatic* or *Off*.
- **App-level encrypted DNS.** Apps that use their own DNS-over-HTTPS (some browsers,
  for example) aren't filtered in *Telemetry* mode.
- **Cutting Google services has side effects.** It disables push notifications for
  many apps, Play Store downloads and updates, Google sign-in, Google Pay/Wallet,
  backups, Find My Device and fast location.
- **Debug builds.** CI builds are signed with a temporary debug key, so uninstall the
  previous version before installing a new one.

## Project layout

```
TelemetryBlocker/                 Android app (Kotlin, no external dependencies)
  app/src/main/java/...           VPN service, packet/DNS handling, UI
  app/src/main/assets/            Built-in telemetry domain list
.github/workflows/                CI build that produces the APK
```

## License

[GPL-3.0](LICENSE)
