# G2 World Anchor — face-tracked prototype

An Android proof-of-concept for a stationary Pixel and Even Realities G2 glasses. The front camera estimates the wearer’s head pose from their face alone. A fixed-world wireframe cube is reprojected from the moving eye pose and streamed to one centered G2 image container.

No marker, printed target, or external tracking aid is used.

## Architecture

```text
Pixel front camera
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
 world cube -> eye transform -> calibrated projection
        |
 288 x 144 monochrome frame
        +---------------------> immediate phone preview
        |
 conflated newest-frame queue (no stale backlog)
        |
 high-priority BLE / EvenHub image protocol
        |
 centered G2 image container
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

## First run

1. Fix the Pixel in portrait orientation with the front camera near eye height, approximately 0.4–1.5 m away.
2. Use even frontal lighting and keep the face unobstructed. Both eyes visible gives the most stable depth estimate.
3. Open **G2 World Anchor** and grant Camera and Nearby Devices permissions.
4. Confirm the Tracking card reports `tracking face`, plausible `x/y/z`, live pitch/yaw/roll, and a nonzero frame rate.
5. Tap **Recenter** while looking naturally toward the phone.
6. Move your head laterally and verify the phone preview cube counter-moves as though fixed in the room. Dashed world-axis lines and the edge locator continue to indicate the anchor when the cube leaves the view.
7. Exit any Even Hub `.ehpk` before tapping **Connect G2**; the Even app and this native app cannot own the glasses BLE connection simultaneously.

The Lens preview reports phone render FPS separately from completed G2 frame FPS and BLE transfer time. The phone path is camera-driven and never waits for BLE; the G2 path keeps only the newest pending frame.

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
