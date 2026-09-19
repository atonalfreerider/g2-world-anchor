# Piano waterfall test setup

This `dev` branch turns the world-anchor prototype into a face-tracked piano
note waterfall for **Hot Cross Buns**. The phone remains the stationary world
reference; no tag, SLAM session, or external tracker is used.

## Physical setup

1. Mount the Pixel vertically on the music stand with the front camera facing
   the player. Keep the full face visible when looking down toward the keys.
2. Sit in the normal playing position. Do **not** connect G2 from the tracker;
   Even Hub will own that connection.
3. Estimate the straight-line distance from the eyes to the playing edge of the
   middle **A** key in the G–A–B group. Adjust **Aim −5cm / +5cm** from the
   0.75 m default.
4. Look directly at that A-key strike point and tap **Set strike**. This fixes the
   center of the three-lane runway in the phone-camera world frame.
5. Use the phone preview to fine-tune the fixed strike line:
   - arrows move it left/right/up/down by 1 cm;
   - **Away / Closer** move it toward the phone / player by 2 cm;
   - **Keys − / +** changes G–A–B spacing by 2 mm;
   - **Depth − / +** changes the virtual runway by 5 cm.
6. Leave the tracker running, switch to Even Hub, and open **G2 Piano
   Waterfall**. Tap **Restart**, then **Play**. The melody loops every 16 beats.
   Adjust tempo in 4 BPM steps while paused or playing.

The two packages communicate only through `127.0.0.1:8080` on the phone. Wi-Fi,
USB, ADB, and the development laptop are not involved after installation.

## Simulated geometry

The calibration point comes from the current filtered head-forward ray and the
selected aim distance. The keyboard plane then uses the stationary phone's
horizontal axis for pitch lanes and extends away from the player toward the
phone. Default white-key spacing is 24 mm and the default virtual runway is
0.60 m with an eight-beat lookahead.

These dimensions are deliberately adjustable because face-derived monocular
depth is approximate. If notes track head motion correctly but miss the keys,
adjust the fixed calibration rather than recapturing until the alignment is
understood.

## Rendering and timing

- The phone preview runs on an approximately 60 Hz animation clock using the
  latest filtered face pose.
- The APK publishes the newest 48×10 frame to a loopback-only server.
- The eHPK uses the full-lens native text container and smallest-span delta
  updates. Both sides conflate: if G2 is slower than animation, obsolete frames
  are discarded rather than accumulating latency.
- `=` marks the strike line, `*` outlines upcoming notes, `#` marks a note at
  the strike line, and the letters G/A/B identify lanes.

The tracker reports phone render FPS. The eHPK reports completed G2 update FPS,
transfer time, and source-frame age so camera/render delay can be distinguished
from glasses transport delay.
