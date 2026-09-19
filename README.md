# G2 World Anchor prototype

An Android proof-of-concept for a Pixel 10 on a fixed stand and Even Realities G2 glasses. The front camera estimates a metric 6-DoF head pose from a small AprilTag mounted rigidly to the glasses. A fixed-world wireframe cube is reprojected from the moving eye pose and streamed directly to one centered G2 image container.

The project builds, runs without the glasses as an on-phone lens simulator, and contains a direct-BLE G2 adapter. Real tracking accuracy and G2 transport still require the two devices.

## Why this architecture

The official Even Hub plugin route is not suitable for continuous tracking. Its phone-camera API is a single-shot picker, while plugins run in a WebView and the documented glasses image path has substantial per-call overhead. ARCore is also not the right front-camera tracker: Google's certified-device page says the selfie camera is not supported for ordinary ARCore world tracking.

A native Android app can own the front camera and BLE connection at the same time. For position accuracy, a measured fiducial is preferable to monocular face geometry: its real edge length fixes metric scale, four sharp corners support `SOLVEPNP_IPPE_SQUARE`, and reprojection error gives a useful quality gate.

```text
Pixel 10 front camera
        |
 CameraX RGBA frames
        |
 AprilTag 36h11 ID 0 -> subpixel corners -> solvePnP
        |
 filtered/predicted 6-DoF eye pose in the fixed phone frame
        |
 world cube -> eye transform -> calibrated perspective projection
        |
 288 x 144 monochrome frame
        +---------------------> on-phone lens preview
        |
 direct dual-arm BLE / EvenHub image protocol
        |
 centered G2 image container
```

## What is implemented

- CameraX continuous front-camera capture with latest-frame backpressure.
- OpenCV AprilTag 36h11 detection, subpixel corner refinement, metric IPPE square pose, a 4 px reprojection-error rejection threshold, and a 0.15–3.0 m working range.
- A pose filter with speed-adaptive smoothing and bounded translation prediction for camera/display latency.
- A rigid forehead-tag-to-cyclopean-eye transform and a world-fixed 16 cm wireframe cube placed 70 cm in front of the wearer on Recenter.
- Perspective rendering for a centered 288×144 G2 image surface, plus an identical on-phone preview.
- Direct G2 BLE scan, dual-arm connection, authentication, heartbeat, image packetization, acknowledgement handling, and session recovery.
- A deliberately single-container display path. Four tiles fill the G2 canvas but require four serial transfers; one centered maximum-size image is the practical animation path.
- Unit tests for rigid transforms, eye-axis convention, quaternion conversion, and latency prediction.

## Build status

From this directory:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./gradlew testDebugUnitTest assembleDebug
```

Opening the directory in Android Studio is the easiest option; it supplies the local Android SDK path automatically. For a command-line build, create the usual uncommitted `local.properties` containing `sdk.dir=/absolute/path/to/Android/Sdk`.

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

To install after connecting the Pixel by USB and enabling USB debugging:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Marker preparation

Use [`marker/apriltag36h11-id0.png`](marker/apriltag36h11-id0.png). Print without smoothing or scaling-to-fit so the **inner black detection square measures exactly 45.0 mm**. The supplied PNG has one white-cell margin around an eight-cell detection square, so its complete printed image should be 56.25 mm wide.

Mount it on a flat, light card above the glasses bridge or on a rigid headband. It must not flex or move relative to the glasses. Keep the whole white margin visible. The marker is intentionally large enough to retain useful corner precision at roughly 0.6–1.2 m.

The marker image is from the [AprilRobotics tag image repository](https://github.com/AprilRobotics/apriltag-imgs), family 36h11, ID 0.

## First hardware run

1. Put the Pixel in portrait orientation, fixed at eye height, with the screen/front camera toward you. Start around 0.8 m away and use diffuse frontal lighting.
2. Disconnect the G2 from the Even app. This prototype owns both G2 BLE arms directly; two phone apps cannot own the same peripherals simultaneously.
3. Launch **G2 World Anchor** and grant Camera, Nearby devices, and Notification permissions.
4. Verify the Tracking card reports `tracking tag 0`, a plausible `z` in meters, reprojection error below about 2 px, and a stable phone preview.
5. Tap **Recenter** while looking naturally toward the phone. Move your head 5–10 cm sideways. In the phone preview the cube should move oppositely, as though fixed in space.
6. Tap **Connect G2**, wait for `ready`, and enable **Stream**. Start with slow lateral motion; the display transport is much slower than the camera tracker.

If horizontal motion is reversed, the physical tag was mounted with a different orientation than the default transform in `Pose3.eyeFromForeheadTag()`. Rotate the printed tag 180°, or change `Mat3.TAG_TO_EYE` after measuring the mount.

## Calibration plan

The current prototype uses two informed estimates so it can run immediately:

- front-camera horizontal FoV: 84°;
- centered G2 image-region horizontal FoV: 13.75° (half of the published 27.5° full display FoV).

For a serious accuracy test, replace those estimates:

1. **Phone intrinsics:** capture a ChArUco calibration set with the exact CameraX resolution/orientation, then store `fx`, `fy`, `cx`, `cy`, and distortion. This mainly improves metric depth and off-axis pose.
2. **Tag-to-eye extrinsics:** measure tag center to midpoint-between-eyes in all three axes. A simple later refinement is to collect several head poses while sighting a fixed target and solve the hand-eye offset that minimizes anchor motion.
3. **Display projection:** show a dot, sight fixed physical targets at known angular offsets, and fit display focal length plus optical center. Do this separately for each wearer/fit.
4. **Latency:** record the phone preview and through-lens result at high frame rate, measure motion-to-photon delay, then tune the predictor. Prediction is currently capped at 220 ms.

Log these acceptance metrics before changing filters:

- static position jitter in mm (RMS over 30 s);
- static angular jitter in degrees;
- anchor drift in display pixels during a ±10 cm lateral head sweep;
- tracking-loss rate and recovery time;
- camera-to-preview and camera-to-glasses latency;
- achieved G2 frames per second.

## Known limits

- This is motion-parallax pseudo-3D, not guaranteed stereoscopic 3D. The public G2 surface presents a 576×288, 16-level monochrome canvas and does not expose independent left/right-eye render targets. The prototype uses a centered 288×144 region to reduce transfer cost.
- Community measurements put small image updates near a 9 fps ceiling and a maximum-size image around 5 fps through the public bridge. Direct BLE may differ, but this will still be a low-frame-rate experiment rather than optical-see-through AR.
- The pose is world-anchored to the **stationary phone**, so moving or vibrating the stand moves the virtual world.
- A single planar tag can become ambiguous at steep angles. A future two-tag rigid board or curved multi-tag mount would improve coverage.
- The direct-BLE G2 transport is based on community reverse engineering and has not been exercised here on physical G2 hardware. Firmware changes may require protocol updates.
- The current renderer predicts translation but not angular velocity. The G2 IMU is available in the official SDK, but the documented event exposes only three unnamed values, so it is not fused until its units and semantics are verified on hardware.

## Recommended next increments

1. Run the phone-only stage and replace the estimated Pixel intrinsics with a calibrated file.
2. Hardware-test BLE authentication and one static frame before enabling continuous streaming.
3. Measure actual single-container frame time; set the stream cadence to the measured completion rate.
4. Add a second non-coplanar tag and jointly solve all visible corners.
5. Add a guided display-calibration screen and persist calibration per wearer.
6. Only then consider fusing G2 IMU rotation for prediction between camera frames.

## Research references

- [Even Hub overview and G2 hardware/plugin architecture](https://hub.evenrealities.com/docs/get-started/overview)
- [Even Hub device APIs: camera is single-shot; G2 IMU is available](https://hub.evenrealities.com/docs/build/device-apis)
- [Official G2 specification: 27.5° FoV](https://support.evenrealities.com/hc/en-us/articles/13499229138959-Specs)
- [Official Even Hub starter templates](https://github.com/even-realities/evenhub-templates)
- [Community G2 display performance measurements](https://github.com/nickustinov/even-g2-notes/blob/main/docs/performance.md)
- [Google ARCore supported-device constraints](https://developers.google.com/ar/devices)
- [AprilTag 3 detector and pose-estimation guidance](https://github.com/AprilRobotics/apriltag)
- [OpenCV ArUco/AprilTag detection and solvePnP guidance](https://docs.opencv.org/5.0/tutorials/objdetect/aruco_detection/aruco_detection.html)
- [OpenCV official Android AAR distribution](https://opencv.org/opencv4android-usage-models/)
- [OpenEvenSdk Android G2 bridge used as the BLE starting point](https://github.com/Thepizzapie/OpenEvenSdk/tree/main/android)

## Source note

`Crc16.kt`, `Protocol.kt`, `EvenHub.kt`, `G2Connection.kt`, `BridgeService.kt`, and `HttpServer.kt` began from the Android bridge in OpenEvenSdk (commit `fa0522ea283744eaf345a788a9fee82a740afd08`, retrieved 2026-09-19) and were adapted for the single-container world-anchor experiment. That upstream repository did not include a license file at retrieval time; treat this prototype as experimental research code and confirm terms with the upstream author before redistribution.
