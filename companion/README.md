# G2 Piano Waterfall — Even Hub companion

This standalone `.ehpk` plays **Hot Cross Buns** as a native-text note
waterfall through the official Even Hub SDK. It uses one full-lens 576×288 text
container represented by a fixed 48×10 ASCII frame.

## Runtime design

- The complete 16-beat G–A–B melody loops at an adjustable tempo.
- A four-beat default lookahead maps simulated depth onto the available rows.
- Converging lane rails and beat bands make the display read as a runway.
- The app computes the smallest contiguous text edit and sends partial
  `TextContainerUpgrade` operations; a conflated queue discards obsolete frames.
- Single-tap on the glasses pauses or resumes playback; double-tap exits.
- No permissions, image transfers, network connection, or external MIDI file
  are required.

The phone-side Even Hub page provides controls for tempo, horizontal centering,
lane spacing, strike row, simulated depth, restart, and pause/play. These are
display-space adjustments, not physical world calibration.

## Build, package, and validate

```bash
npm ci
npm run pack
npm run validate
```

The package is written to
`../build/g2-piano-waterfall-evenhub-v0.2.0.ehpk`.

## Tracking boundary

The Even app owns the glasses connection while this package runs, so the native
Pixel APK cannot simultaneously stream its face-tracked world projection. Exit
the `.ehpk` before testing the APK's direct-BLE path. Use this package for a
fast, standalone text-waterfall baseline; use the APK for head-tracked physical
keyboard alignment.
