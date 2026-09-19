# G2 World Anchor Lab — Even Hub companion

This `.ehpk` is the G2-side display-calibration companion for the native **G2 World Anchor** Android prototype.

It deliberately does not duplicate the native runtime. The APK owns continuous
front-camera face/head-pose tracking, recentering, filtering, world projection,
and its direct-BLE experiment. No tag or marker is involved. This companion uses
the official Even Hub SDK to validate the display assumptions independently:

- one centered 288×144 image container at `(144, 72)` on the 576×288 canvas;
- anchor-cube, optical-center, and geometry-grid patterns;
- a serialized transport-pulse mode that reports completed image transfers per second;
- single-tap to advance patterns and double-tap to exit;
- no requested plugin permissions and no network dependency.

## Build, package, and validate

```bash
npm install
npm run pack
npm run validate
```

The package is written to `../build/g2-world-anchor-companion.ehpk`.

The validator checks the manifest against the installed SDK, verifies all built HTML asset references, checks the package signature, and prints the artifact SHA-256.

## Hardware use

Install the `.ehpk` through the Even Hub developer workflow and open **G2 World Anchor Lab** while the Even app owns the glasses connection. Use the three static patterns to inspect centering, clipping, and optical alignment. Use **Transport pulse** only long enough to measure the completed transfer rate.

Exit the companion before testing the Android app's direct-BLE path: both the Even app and the native prototype cannot own the same G2 peripherals at the same time.
