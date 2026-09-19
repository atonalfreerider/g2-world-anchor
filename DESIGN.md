# Face-tracked world-anchor design

## Objective

Demonstrate a world-stable monocular cube on G2 using only the stationary Pixel’s front camera and on-device face/head-pose estimation. The wearer should need no printed marker or external tracking hardware.

On the `dev` branch, the cube is replaced by a three-lane piano runway fixed to
a user-calibrated strike point. The 16-beat Hot Cross Buns sequence advances on
a 60 Hz monotonic animation clock; phone preview rendering is independent of
face-inference cadence, while the G2 path retains only the newest text frame.

## Core decisions

| Area | Decision | Reason |
|---|---|---|
| Phone runtime | Native Android app | CameraX and BLE can run continuously with explicit lifecycle control. |
| Background lifetime | Camera + connected-device foreground service | App switches do not pause face inference or freeze the last G2 frame. |
| Detection | Bundled ML Kit face detector | On-device operation, no marker, no first-run model download. |
| Rotation | Face pitch/yaw/roll | Direct head-orientation output from the detector. |
| Translation | Eye midpoint plus apparent IPD | Produces approximate cyclopean-eye position from face landmarks alone. |
| World frame | Fixed phone front-camera frame | The stationary phone supplies a local room reference without SLAM. |
| Stabilization | Adaptive pose filter plus bounded translation prediction | Reduces face-landmark shimmer and part of transport latency. |
| Display | Full-lens 576×288 native text container | Compact in-place updates cover the whole canvas without bitmap-transfer latency. |
| Scheduling | Camera-driven preview plus conflated G2 queue | Phone rendering never blocks on BLE and obsolete frames are discarded. |
| Locator | Dashed world-axis vanishing lines plus edge marker | Keeps the fixed anchor direction legible outside the narrow display. |

## Pose estimate

The detector returns face orientation and eye landmark pixels. With approximate focal length `f`, observed eye separation `d_px`, assumed real IPD `d_m`, and yaw `y`, depth is estimated as:

```text
z = f * d_m * cos(y) / d_px
x = (eyeCenterX - cx) * z / f
y = (eyeCenterY - cy) * z / f
```

The cosine term compensates approximately for yaw foreshortening. At angles where both eye landmarks are unavailable, face-box width provides a lower-confidence fallback scale. Neutral gaze points toward the phone along camera `-Z`; head Euler rotations are composed around that neutral frame.

## Acceptance checks

- `tracking face` without any marker in view;
- stable tracking at 0.4–1.5 m under normal indoor light;
- correct signs for lateral translation, yaw, pitch, and roll;
- Recenter places the cube along current gaze;
- the phone preview counter-moves during a slow lateral head sweep;
- phone render FPS remains independent of completed G2 transfer FPS;
- vanishing guides remain tied to the fixed anchor and an edge marker appears off-screen;
- no OpenCV or AprilTag code/library in the APK;
- sustainable G2 text-frame rate measured separately from detector rate.

## Important limitations

Face detection is not metric 6-DoF tracking by itself. Translation scale depends on assumed IPD, estimated camera intrinsics, landmark quality, and head angle. This design satisfies a marker-free interaction requirement, but it trades away the known scale and corner precision of a fiducial. Per-wearer IPD calibration and measured camera intrinsics are the first accuracy upgrades.

## Failure modes

| Symptom | Likely cause | Mitigation |
|---|---|---|
| Depth breathes | Landmark spacing noise or wrong IPD | Enter wearer IPD; strengthen temporal filtering. |
| Pose drops on profile | One eye is occluded | Use face-width fallback; limit operating yaw. |
| Horizontal motion reverses | Front-camera coordinate convention mismatch | Flip camera X once after physical validation. |
| Cube trails motion | Camera/model/BLE latency | Measure latency, then tune bounded prediction. |
| Whole world shifts | Phone stand moved | Use a rigid weighted stand. |
| G2 disconnects | Even app still owns BLE | Exit the `.ehpk` before native direct-BLE testing. |
