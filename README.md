# Interframe

**AI frame generation for Minecraft** — NeoForge 1.21.1. Captures each finished frame and inserts
synthesised *in-between* frames to raise the perceived frame rate and smooth motion. Built to slot into
the same stack as [Foveate](../Foveate): NeoForge 21.1, Sodium 0.8, Iris, and Reese's Sodium Options.

> Think DLSS Frame Generation / Lossless Scaling, but as a Minecraft mod and inside the GL pipeline.

## How it works

Once per frame, at the head of `Window.updateDisplay()` (right before Minecraft swaps buffers), Interframe:

1. **Captures** the final composited image from the main render target via a GPU framebuffer blit — so it
   sits *downstream* of Sodium, Iris and Foveate's VRS pass and sees exactly what you'd see. Scene depth
   is grabbed earlier, at the end of the level render (the last moment before vanilla clears it for the
   hand/GUI), for the translational warp.
2. **Synthesises** one or more in-between frames and **presents** each with its own
   `RenderSystem.flipFrame()` swap, **paced onto an even schedule** (below).
3. **Re-blits** the real frame to the back buffer so Minecraft's own swap shows it.

Net display order: `… N-1, [in-between…], N, [in-between…], N+1 …` — extra unique images between the real
ones, evenly spaced in time, which is what your eyes read as smoothness.

### Frame pacing (why this works at all)

Inserting a frame is pointless if it's swapped microseconds before the next one — the monitor never scans
it out. Interframe therefore *schedules* presents:

- **V-Sync off:** synthetic frame *i* is presented at `i·interval/(g+1)` into the frame, and the real
  frame is held until its own slot. That hold is the classic interpolation latency cost (~half a frame at
  2x) and is exactly what every frame-generation product pays; the **Frame Pacing** setting scales it.
  The waits are hitch-hardened, hard-capped, and the generator subtracts its own added delay from its
  interval estimate so pacing can never feed back on itself.
- **V-Sync on:** every swap already blocks until a vblank — the display does the pacing. Interframe
  inserts only as many frames as there are *empty vblank slots* in the measured interval (30 real FPS on
  a 60 Hz panel has exactly one free slot → clean 2x; at/near refresh it inserts nothing rather than
  halving your real rate).

## Synthesiser modes (graceful degradation)

Set in **Video Settings → Interframe** (the Sodium menu, also rendered by Reese's Sodium Options).

| Mode | What it does | Needs |
|------|--------------|-------|
| **Blend** | Cross-fade prev/next at the in-between time. Always correct; soft ghosting under fast motion. | nothing |
| **Reproject** *(default)* | Full-pose reprojection: **rotation** is warped exactly from the cameras' orientation quaternions (correct at any yaw/pitch combination, not a small-angle Euler approximation), and **translation** — walking, strafing, flying — is parallax-corrected per pixel using the scene depth buffer (easing out for very near geometry, where a soft blend beats a stretched warp). Sprint/zoom FOV changes are interpolated too. | nothing |
| **Timewarp** | Warps the newest frame *forward* along a time-based (rad/s, m/s) velocity estimate so the view leads your motion — lowest latency, slight overshoot on direction changes. | nothing |
| **Neural (RIFE)** | A learned interpolation model run through **ONNX Runtime** — handles object motion, not just camera motion. | model file + runtime (below) |

The built-in backends need no model and run on any GL 3.2 GPU. They are the default and the automatic
fallback if the neural path is unavailable.

## Enabling the neural (RIFE) backend

ONNX Runtime is **not bundled** (its native libraries are large and GPU/OS-specific), so you opt in:

1. **Add ONNX Runtime to the classpath.** Drop an `onnxruntime` jar into the instance (e.g. as a
   library/mod the loader exposes), or `onnxruntime_gpu` (CUDA) / a DirectML build for GPU inference.
   Interframe selects a CUDA → DirectML → CPU provider automatically (reflectively).
2. **Provide a model** at `config/interframe/model.onnx`.
3. Set **Synthesiser → Neural** in the menu.

**Supported model signature** (validated at load — mismatches log and fall back, they don't crash):

- ≥ 2 image inputs shaped `NCHW` with a channel dimension of 3 (the two frames, RGB, values in `[0,1]`),
- exactly 1 image output of the same form (the interpolated frame),
- dynamic height/width,
- an optional extra small/scalar input, which receives the in-between **timestep** (`0..1`). Models without
  it are treated as midpoint-only (always 0.5).

This matches common RIFE ONNX exports. Inference runs at a capped resolution (longest edge ≤ 960 px) and is
bilinearly upscaled to the screen, since per-frame readback + inference is the cost of true learned
interpolation; it only nets a win if the model is fast enough on your GPU.

## Settings

- **Frame Generation** — master on/off.
- **Synthesiser** — Blend / Reproject / Timewarp / Neural (above).
- **Generated Frames** — 2x / 3x / 4x (insert 1, 2 or 3 frames per real frame; with V-Sync on, capped to
  the free vblank slots).
- **Reprojection Strength** — how strongly camera motion is warped (Reproject/Timewarp/Neural).
- **Translation Warp** — depth-based parallax compensation for camera movement (default on).
- **Look-Ahead** — Timewarp only: how far ahead the displayed frame is warped (latency compensation).
- **Max Warp / Frame** — above this per-frame rotation (or a >5-block position jump), treat as a cut and
  just blend (no smear on respawn/teleport).
- **Frame Pacing** — how evenly presents are spread across the frame interval (default 90%; 0 = present
  immediately, lowest latency but little visible smoothing with V-Sync off).
- **In-Game Only** — don't generate on menus/loading screens.

Config persists to `config/interframe.json`.

## Compatibility

- **Sodium 0.8 / Reese's Sodium Options** — registers its page through Sodium's config API
  (`@ConfigEntryPointForge`), exactly like Foveate, so it appears in the video settings either way.
- **Iris** — captures after Iris composites; shader output is interpolated like anything else. With a
  shaderpack active, the **translational** warp auto-disables (the vanilla depth buffer isn't the pack's);
  rotational reprojection still applies.
- **Foveate** — different pipeline stage (frame *presentation* vs. terrain shading rate). Load Interframe
  *after* Foveate so capture sees the VRS-composited frame. They stack.
- **Fovea** — during Fovea's center-priority (warped) frames the scene depth lives in Fovea's scaled
  source buffer while the captured colour is the warped presented image, so the **translational** warp
  pauses for exactly those frames (detected per frame via a reflective bridge, since 1.2.0) and the
  rotational warp corrects its half-FOV tangent for Fovea's trim — exact in the presented 1:1 center.
  Uniform-mode and standing-still frames keep full parallax. Without this, entities/water/ice landed at
  the wrong screen positions on every synthesised frame while moving.

## Honest limitations

- This is **interpolation in the present loop**: at 2x the even pacing costs ~half a real frame of added
  latency (Timewarp mode instead *reduces* perceived latency, with mild overshoot). It is not a
  zero-latency async-reprojection compositor.
- Translation compensation uses a single depth tap per pixel; very fast strafing right past near geometry
  can show thin halo artefacts at object edges (disocclusions), which the blend softens.
- Moving **entities** cross-blend (soft) in the built-in modes — motion-compensating them is what the
  **Neural** backend is for.
- The HUD and first-person hand are part of the captured frame (they draw after the depth capture), so
  they warp very slightly with the camera on synthetic frames — as in every frame-generation product
  without engine-level HUD separation. At 2x with normal mouse speed this reads as a faint crosshair
  shimmer at most.
- Built and verified to compile against MC 1.21.1 sources; visual tuning depends on your GPU/display and
  needs in-game testing.

## Building

```
./gradlew build
```

Produces `build/libs/interframe-<version>.jar`. Requires JDK 21. Same toolchain as Foveate
(`net.neoforged.moddev`); compiles against Sodium 0.8's config API extracted from its JiJ bundle.

## License

MIT.
