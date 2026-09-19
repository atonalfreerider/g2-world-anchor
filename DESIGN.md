# Design plan

## Objective and success criterion

Demonstrate that a stationary Pixel 10 can make a simple cube appear fixed in the room while the wearer translates and rotates their head. The first useful result is not “perfect AR”; it is measured evidence that residual anchor motion and latency are low enough to be convincing on the G2 display.

Target the first hardware session at:

- continuous metric pose at 20+ camera estimates/s;
- less than 5 mm static translation jitter after filtering;
- less than 1° static orientation jitter;
- at least 5 completed G2 image frames/s;
- no more than 250 ms camera-to-glasses latency;
- reacquisition within 0.5 s after a short occlusion.

## Key decisions

| Area | Decision | Reason |
|---|---|---|
| Phone runtime | One native Android app | It can own continuous CameraX capture and both BLE arms together. |
| Tracking target | 45 mm AprilTag 36h11 ID 0 rigidly mounted to the glasses | A measured fiducial gives metric scale and sharper, more auditable pose than face landmarks. |
| World frame | Fixed phone front-camera frame | The stand makes it a stable local reference without SLAM. |
| Pose solve | Subpixel corners + `SOLVEPNP_IPPE_SQUARE` | Specialized for square planar targets and returns full metric 6-DoF pose. |
| Stabilization | Adaptive low-pass plus bounded latency prediction | Reduces stationary shimmer without making movement needlessly sluggish. |
| Display | One centered 288×144 image container | Avoids the four serial transfers needed for a full 576×288 image. |
| Depth cue | Monoscopic perspective and motion parallax | Independent per-eye SDK rendering is unavailable; this still tests world anchoring. |
| Transport | Direct G2 BLE bridge behind `G2Connection` | The official plugin camera API is single-shot and cannot supply continuous frames. |

## Coordinate chain

All persistent world data uses the phone-camera coordinate system. The tracker estimates `T_camera_tag`. A measured mount transform produces `T_camera_eye`:

```text
T_camera_eye = T_camera_tag × T_tag_eye
```

Recenter creates a cube center 0.70 m along the eye's forward axis but stores it in camera/world coordinates. Every frame transforms each fixed cube vertex back into the current eye frame and applies the calibrated display projection:

```text
p_eye = inverse(T_camera_eye) × p_world
u = cx + fx × p_eye.x / p_eye.z
v = cy + fy × p_eye.y / p_eye.z
```

Vertices behind or extremely close to the eye plane are clipped.

## Delivery stages

### Stage 0 — completed without hardware

- Research platform limits and choose native Android/direct BLE.
- Implement CameraX/OpenCV tracker and pose-quality gates.
- Implement math, filtering, prediction, cube renderer, and phone preview.
- Adapt the G2 bridge to a centered single image surface.
- Build the debug APK and pass JVM unit tests.

### Stage 1 — Pixel only

- Install APK, grant permissions, and verify CameraX selects the front camera.
- Confirm pose distance against tape-measured 0.6, 0.8, and 1.0 m positions.
- Move the tag on a translation jig or ruler and log scale error/jitter.
- Replace the estimated front-camera intrinsics with a calibration file.
- Confirm the on-phone cube exhibits correct, stable counter-motion.

Exit condition: metric scale error under 2%, static jitter under 5 mm, and no axis reversal.

### Stage 2 — G2 static transport

- Ensure the Even app has released both BLE arms.
- Connect and verify the seven-packet authentication reaches `READY`.
- Send a static cube and inspect location, clipping, brightness, and persistence.
- Measure one-container transfer time and dropped acknowledgements.

Exit condition: ten consecutive static image sends succeed without reconnecting.

### Stage 3 — Dynamic anchor

- Stream at the measured sustainable completion rate rather than a blind timer.
- Film head motion and through-lens response at high frame rate.
- Fit display focal length/center and translation-prediction horizon.
- Run slow translation, yaw, pitch, and combined-motion trials.

Exit condition: the cube visibly stays closer to the same real point than to the glasses during a ±10 cm lateral sweep.

### Stage 4 — accuracy upgrade

- Add calibrated lens distortion.
- Replace one planar tag with a rigid two-face tag mount for wider pose coverage.
- Solve tag-to-eye extrinsics through a guided sighting calibration.
- Investigate G2 IMU units and fuse orientation only if timestamps and axes are trustworthy.

## Failure modes and mitigations

| Risk | Observable symptom | Mitigation |
|---|---|---|
| Wrong marker print scale | All translations/depth are proportionally wrong | Measure the inner black square with calipers and enter its actual size. |
| Estimated camera intrinsics | Depth changes as the tag moves off-center | Run ChArUco calibration at the exact analysis resolution. |
| Planar-pose ambiguity | Sudden orientation flip near frontal view | Reject jumps, use temporal prior, then add a second non-coplanar tag. |
| Flexible marker mount | Cube moves when the glasses do not | Use rigid card/plastic fixed to the frame or headband. |
| BLE throughput/latency | Cube trails motion or updates in steps | Keep one image container, conflate poses, predict to display time. |
| G2 protocol/firmware change | Authentication or image ACKs fail | Capture logs, compare wire frames, and keep tracking/rendering testable without BLE. |
| Phone movement | Entire virtual world shifts | Rigid, weighted stand; disable vibration; do not touch after Recenter. |
| Optical calibration mismatch | Stable but angularly misregistered cube | Fit display focal length and optical center for the wearer and frame fit. |

## Go/no-go decision after the experiment

Proceed to calibration and multi-tag work if world anchoring is directionally correct and display latency is the main residual error. Stop pursuing this G2 path if measured image throughput is too low for tolerable motion parallax or if independent-eye output is a hard requirement; those are platform limits rather than tracking bugs.
