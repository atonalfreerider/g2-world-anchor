# G2 Piano Tracker + Waterfall

An on-phone, face-tracked piano note waterfall for a stationary Pixel and Even
Realities G2 glasses. The front camera estimates the player's head pose without
a tag. **Hot Cross Buns** is projected into a calibrated keyboard space and
rendered through G2's fast native-text path.

No marker, printed target, or external tracking aid is used.

The complete runtime uses two installable phone packages. A laptop is needed
only to build or install them:

```text
G2 Piano Tracker APK                  G2 Piano Waterfall eHPK
camera + head pose + calibration  -> localhost newest-frame bridge
                                      -> Even Hub SDK -> G2 display
```

Only Even Hub owns the glasses connection. The APK's direct G2 controls are an
optional transport diagnostic and must remain disconnected during normal use.
See [PIANO_TESTING.md](PIANO_TESTING.md) for setup and calibration.

## Repository layout

```text
app/          Android tracking, calibration, preview, and loopback frame server
companion/    Even Hub G2 display client
gradle/       Pinned Gradle wrapper
```

This is a single source repository. Build outputs, packaged APK/eHPK files,
dependency directories, IDE state, signing files, and local task scratch data
are intentionally excluded from Git.

## Architecture

```text
Pixel front camera (G2 Piano Tracker APK)
        |
 CameraX YUV frames
        |
 bundled ML Kit face detector
        |
 eye landmarks + head pitch/yaw/roll
        |
 approximate cyclopean-eye position and orientation
        |
 filtered/predicted head pose in the fixed phone frame
        |
 fixed piano runway -> eye transform -> calibrated projection
        |
 full-resolution phone preview + newest 48 x 10 ASCII frame
        +---------------------> immediate phone preview
        |
 loopback-only HTTP + CORS (127.0.0.1:8080)
        |
 G2 Piano Waterfall eHPK in the Even phone app
        |
 conflated smallest-span native-text updates
        |
 full-lens 576 x 288 native text container
```

## Tracking model

- ML Kit’s face model is bundled in the APK, runs on-device, and does not need a first-run model download.
- Head pitch, yaw, and roll come from the detected face orientation.
- Lateral and vertical eye position come from the midpoint between the detected eye landmarks.
- Depth is estimated from apparent eye separation using a 63 mm population-average interpupillary distance.
- If both eyes are unavailable at a steep angle, depth temporarily falls back to detected face width using a 145 mm average.
- The pose filter smooths noise and predicts bounded translation to offset part of the camera/display latency.

The depth estimate is approximate and wearer-dependent. For accurate scale, replace the average IPD with the wearer’s measured IPD and calibrate the Pixel front-camera intrinsics.

## Build

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/usr/lib/android-sdk
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

### Even Hub companion

```bash
cd companion
npm ci
npm run pack
npm run validate
```

The companion is written to
`build/g2-piano-waterfall-evenhub-v0.3.0.ehpk`. It requires the tracker APK on
the same phone and requests network access only for `127.0.0.1:8080`.

## First run

1. Install the APK and eHPK on the phone.
2. Fix the Pixel vertically on the music stand with the front camera seeing your
   face while you look down at the keyboard.
3. Open **G2 Piano Tracker** and grant Camera. Nearby Devices is not needed for
   the normal Even Hub workflow.
4. Confirm live face data, choose the eye-to-key aim distance, look at the
   middle A-key strike point, and tap **Set strike**.
5. Leave the tracker running, open Even Hub, and launch **G2 Piano Waterfall**.
6. Keep **Direct G2 debug** off in the tracker. Play/restart/calibrate from the
   phone; a single glasses tap toggles playback.

The phone renderer never waits for the glasses. The APK publishes a conflated
newest frame at its animation rate; the eHPK polls locally at up to 50 Hz and
conflates again while a glasses update is in flight. Consecutive fixed-size text
frames are diffed so only the smallest changed span crosses the G2 link.
CameraX and the local server are owned by a camera-typed foreground service, so
they continue when Even Hub covers the tracker activity.

The BLE protobuf framer is boundary-tested around the 232-byte ATT chunk size. Transport failures are contained inside the stream worker and surfaced in the UI instead of terminating the Android process.

## Calibration priorities

1. Enter the wearer’s measured IPD instead of the 63 mm default.
2. Calibrate front-camera focal length and optical center at the actual CameraX resolution.
3. Fit G2 display focal length and optical center with fixed physical targets.
4. Measure camera-to-preview and camera-to-glasses latency before tuning prediction.

## Known limits

- This is monocular head-pose estimation, not metric SLAM. Depth scale depends on facial geometry and can drift with yaw or partially hidden eyes.
- Face Euler angles and landmarks are detector outputs, so rapid motion, dim light, occlusion, and extreme head rotation reduce accuracy.
- The stationary phone defines the world frame; moving the phone moves the virtual world.
- The G2 public surface does not expose independent per-eye render targets, so the prototype demonstrates motion parallax rather than guaranteed stereoscopic 3D.
- Direct G2 BLE transport is experimental and must not run concurrently with the Even app’s glasses session.

## References

- [ML Kit face detection on Android](https://developers.google.com/ml-kit/vision/face-detection/android)
- [ML Kit face orientation and landmark concepts](https://developers.google.com/ml-kit/vision/face-detection/face-detection-concepts)
- [Even Hub overview](https://hub.evenrealities.com/docs/get-started/overview)
- [Official G2 specification](https://support.evenrealities.com/hc/en-us/articles/13499229138959-Specs)

## Source note

`Crc16.kt`, `Protocol.kt`, `EvenHub.kt`, `G2Connection.kt`, `BridgeService.kt`, and `HttpServer.kt` began from the Android bridge in OpenEvenSdk commit `fa0522ea283744eaf345a788a9fee82a740afd08` and were adapted for this experiment. That upstream repository did not include a license file at retrieval time; confirm terms with its author before redistribution.
