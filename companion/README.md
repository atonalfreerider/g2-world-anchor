# G2 Piano Waterfall — Even Hub companion

This `.ehpk` is the G2 display half of the phone-only piano system. It does not
simulate tracking and it does not need a laptop or LAN server. The Even app and
the native **G2 Piano Tracker** APK run together on the same Android phone:

```text
front camera -> Piano Tracker APK -> 127.0.0.1:8080 -> this eHPK -> G2
```

The APK publishes only the newest 48×10 face-tracked waterfall frame. This
package polls that loopback endpoint, conflates obsolete frames, and sends the
smallest contiguous native-text update through the official Even Hub SDK.
Even Hub is the sole owner of the glasses connection.

## Install and run

1. Install `g2-piano-tracker-v0.5.0.apk` on the phone.
2. Import/install `g2-piano-waterfall-evenhub-v0.3.0.ehpk` in Even Hub.
3. Open **G2 Piano Tracker**, grant Camera, make sure tracking is live, and tap
   **Set strike** while looking at the middle A-key strike point.
4. Leave the tracker running and return to Even Hub.
5. Open **G2 Piano Waterfall**. Do not connect the app's optional direct-BLE
   debug mode.

The tracker foreground service keeps camera tracking and the loopback endpoint
alive while Even Hub is in front. Single-tap the glasses to pause/play;
double-tap exits. Phone controls in the eHPK forward to the tracker.

## Build, package, and validate

```bash
npm ci
npm run pack
npm run validate
```

The package is written to
`../build/g2-piano-waterfall-evenhub-v0.3.0.ehpk`. Its only permission is
network access whitelisted to `http://127.0.0.1:8080`.
