# FSR-UPSC — AMD FidelityFX (FSR) upscaler mod for Project Zomboid B42

A mod dedicated to FSR 1/2/3 plus software frame generation, built on a **completely
new namespace `fsrupsc`**.

- **FSR 1**: spatial upscaling (EASU + RCAS). The automatic fallback target when
  compute is unavailable or faults
- **FSR 2.1 / FSR 3.1**: temporal upscaling (pure GL compute, no native bridge).
  See `docs/PHASE2-FSR3.md` / `docs/PHASE2-FSR3-GLSLDESIGN.md` for details
- **Software frame generation** (`FgPresent`, `FsrControl.fsr2FgTest`):
  a double-present scheme on the present path

Implementations referenced: `mc-fsr` (Fabric, MIT), `fsr-reloaded` (FSR2/3 GLSL compute).

This repository is the public release. The measurement-only scaffolding that existed
in the test build (outline/cone trace, mv probe, swap interval histogram, periodic
log emit) has been removed because it is needless overhead in actual use. Only the
settled fixes and feature toggles remain in the main body. Development-era
diagnostic records are archived in `docs/DIAGNOSIS-HISTORY.md`.

## Layout

```
42/                  mod body (mod.info, media/lua, media/shaders)
agent/               prebuilt agent jars, one folder per game build
  42.21/ 42.22/      fsr-upsc-agent.jar (build artifacts)
src/fsrupsc/         javaagent source (single package: fsrupsc)
scripts/             build-agent.ps1 / install-agent.ps1 / rebrand.ps1 (one-time conversion record)
vendor/lib/          byte-buddy 1.18.8 (unshaded)
docs/                design documents + development diagnostic log (DIAGNOSIS-HISTORY.md)
```

## Build

```powershell
# Game directory and revision default to the script values (E:\...\ProjectZomboid, 4a0e9546ec)
powershell -ExecutionPolicy Bypass -File scripts\build-agent.ps1
```

- Requires JDK 25 (auto-detected from `C:\Program Files\Java` as well)
- Reads `zombie/GitVersion` from `<game>/projectzomboid.jar` and **aborts the build on a
  revision mismatch** (with no arguments the script targets the installed game;
  supported builds map revision hash → output folder: `4a0e9546ec` → `42.21`,
  `e013dbb529` → `42.22`). The agent itself also waits for the same hash as a string
  and runs stock otherwise.
- Output: `agent\<game build>\fsr-upsc-agent.jar` (byte-buddy unpacked into the jar;
  self-contained). Each jar embeds `fsrupsc/build-info.properties` (`revision=<hash>`)
  plus `Target-Revision` in its manifest, which the PZ JAM Launcher revision gate reads.

## Install / Uninstall

Quit the game first. **Do not run another render-scale agent mod at the same time**
(double-patch conflict). The install script auto-detects the installed game build and
copies the matching `agent\<game build>\fsr-upsc-agent.jar`.

```powershell
powershell -ExecutionPolicy Bypass -File scripts\install-agent.ps1            # attach
powershell -ExecutionPolicy Bypass -File scripts\install-agent.ps1 -Detach    # remove vmArgs only (for testing)
powershell -ExecutionPolicy Bypass -File scripts\install-agent.ps1 -Uninstall # vmArgs + jar removal
```

vmArgs written by attach:

```
-javaagent:fsr-upsc-agent.jar
--enable-native-access=ALL-UNNAMED
```

**Nothing else is required** (the hook mask defaults to all groups, including
`present`; the downscale master defaults to ON). Optional override channels for
narrowing/disabling:

```
-Dfsrupsc.hooks=all            (narrow to: loop,internal,fbo,draw,viewport,present — FG test requires present)
-Dfsrupsc.downscale=false      (disable the downscale path for completely stock behavior)
```

Priority: env `FSRUPSC_DOWNSCALE` > `~\Zomboid\fsr-upsc-downscale` file > `-Dfsrupsc.downscale`
(the PZ JAM Launcher drops unknown `-D` flags, hence the multi-level fallback. The same
applies to hooks: env `FSRUPSC_HOOKS` > `~\Zomboid\fsr-upsc-hooks` > `-Dfsrupsc.hooks`)

## Configuration (in game)

Main Menu → Options → Mods → **FSR-UPSC** (`FSR_UPSC_Options.lua` → saved to
`Zomboid\Lua\ModOptions.ini`, read at startup by premain)

| Item | Values |
|---|---|
| Upscaler | off / bicubic / **fsr1** / **fsr2** / **fsr3** (automatic fallback to fsr1 when compute is unavailable or faults) |
| Quality preset | Ultra Quality (1/1.285) / **Quality (1/1.444)** / Balanced (1/1.667) / Performance (1/2) / Native |
| Explicit scale % | 0 (preset) / 50 / 60 / 69 / 78 (with 16px snapping) / **83 (fixed 1600x900** — the least degrading downscale choice on a 1920x1080 display. A height of 900 is not rounded to 896 by the 16px snap) |
| RCAS sharpening | 0–100% (default 80) |
| Frame generation | off / 2-duplicate insertion / 3-interpolated insertion (applied immediately, default off) |
| FG pacing | off (pace by VSync, needs VSync ON) / safe (pace by sleep, VSync OFF) / fast (pace by sleep, VSync OFF, may tear) (applied immediately) |

fsr2 / fsr3 run as real passes on GL 4.3 compute-capable GPUs (verified). On a compute
fault the frame is rescued with fsr1, and the fault pins fsr1 for the rest of the session.

FG warp direction flip (`fsr2FgMvSign`) and manual exposure fine-tuning
(`fsr2FgPace(N)`, 0.1 ms units) are console-only (not exposed in the options UI).
The off/safe/fast values of `fsr2FgPace` are persisted to the settings from both the
options UI and the console.

## Frame generation (FgPresent)

A double-present scheme on the present path (`Display#swapBuffers`). One loop
presents one real frame plus one inserted frame, and the timing of these two flips
needs a **pacer** — either VSync or the mod's own sleep. Exactly one of the two must
be active: the FG pacing option selects which one, and each pacing mode pairs with
exactly one VSync setting (see the table below; the default pairing is FG pacing
`off` + **VSync ON**). With the default pairing the loop stretches to 2 vsyncs —
PZ's FPS readout dropping to about half is normal, while the screen presents inserted
frames at the full display rate. **Only when you pass a hook-narrowing setting** keep
`present` included (with the default `all`, nothing to do — the agent's default keeps
`present` active even via external launchers).

Inserted frames are built from copies of the two real frames before and after
(the world+UI composited back buffer):

- **fsr2FgTest(2) duplicate insertion**: replay of the real frame. **Recommended (stable)**.
  A pure cadence doubling; no flicker
- **fsr2FgTest(3) interpolated insertion**: warp-blends the before/after copies with a
  per-pixel MV field (falls back to camera shift when compute is not live, and to
  duplication when even that is impossible). Optional. The mvScale 0.25 (= half the
  shift of the MV field; matches both probe measurement and theory) is the code default

```lua
FsrControl.fsr2FgTest(0|2|3)
FsrControl.fsr2FgMvSign(-1|1)     -- flip the warp direction (default = sign verified in M2)
FsrControl.fsr2FgMvScale(0.25)    -- per-pixel half-shift scale (console only)
FsrControl.fsr2FgMult(1|2|3)      -- insertions per real frame (default 1 = 2x baseline)
FsrControl.fsr2FgPace(0|-1|-2|N)  -- pacing: 0 pairs with VSync ON, -1/-2/N with VSync OFF
FsrControl.fsr2FgHudSnap(0|1)     -- snap replay of static HUD text (default 1, console only)
FsrControl.fsr2FgMvFlip(0..31)    -- MV field sign/axis experiment toggle (default 0 = correct, console only)
FsrControl.fsr2OffSign(±1)
FsrControl.status()               -- check fgtest= real= inserted= interp= pxmv= pace=
```

`fsr2FgPace` details. The pairing rule is: **choose one pacer** — VSync paces the
loop (pacing `off`), or the mod paces it by sleep (pacing `safe`/`fast`/`N`). Mixing
both pacers, or leaving the loop with none, breaks the cadence; what to pair:

| `fsr2FgPace` | Pair with | Result |
|---|---|---|
| `0` = off (default) | **VSync ON** | VSync paces the loop (2 vsyncs per real+inserted pair). **Most stable.** No tearing. |
| `-1` = safe | **VSync OFF** | The mod paces with full-refresh exposure. Same cadence as `off` (loop = display rate / 2), no tearing. The VSync-OFF counterpart of `off`. |
| `-2` = fast | **VSync OFF** | The mod paces with half-refresh exposure. Loop ≈ display rate (higher cadence than safe), but a flip can land mid-scanout, so occasional tearing/flicker is possible. |
| `N > 0` = manual | VSync OFF | Console-only exposure in 0.1 ms units (167 ≈ 60Hz full exposure / 83 ≈ 60Hz half exposure). |

Wrong pairings and their symptoms:

- `off` + **VSync OFF**: no pacer at all. Both flips flush immediately, the insertion
  cadence collapses (judder/flicker).
- `safe`/`fast` + **VSync ON**: two pacers fight. VSync already blocks every flip, so
  the mod's sleep stacks on top of the vsync wait and the pacing drifts (slow /
  stutter).

Before the inserted flip, exposure for one prior real frame is guaranteed
(spacing = exposure × 2); after the inner swap the code sleeps for just the
exposure window and then restores the real frame. Scanout alignment is not controlled,
so slight tearing at the boundaries can remain.

Known limits of inserted frames: the UI is one frame stale (inherited from the copy of
the world+UI composited back buffer), fast-moving entities are weighted toward the
current side by the mismatch weighting while still smearing slightly. Still /
zoom-changing / reset / no-fresh-dispatch (frames whose dispatch epoch is unchanged,
e.g. loading and pause) fall back to duplication.

**Fine-flicker conclusion (2026-10-26)**: interpolated (fsr2FgTest(3)) inserted frames
are a blend of two real frames, so frame-wide micro flickering is inherent in principle
when viewed closely (it originates in being alternated with crisp real frames). For
static screen HUD text (inventory rows, dashboard digits, etc.), `fsr2FgHudSnap`
(default ON) snaps changed pixels to a replay of the current copy alone and removes the
double ghost; the world itself was judged unavoidable. Duplicate insertion
(fsr2FgTest(2)) is stable, so the **recommended configuration is the duplicate side**.

## Live control (Lua → Java)

The agent injects advice into `LuaManager$Exposer#exposeAll` and registers
`fsrupsc.FsrControl` into the environment at Lua initialization. Success is confirmed by
`[fsr-upsc] exposed fsrupsc.FsrControl (+FsrControl alias)` in the console.

```lua
local fsr = rawget(_G, "FsrControl")            -- top-level alias
-- or: local fsr = rawget(rawget(_G, "fsrupsc"), "FsrControl")
fsr.setUpscaler("fsr1") ; fsr.setUpscalerQuality("balanced")
fsr.setUpscalerScalePct(60) ; fsr.setFsrSharpnessPct(85)
fsr.status()        -- upscaler/mode/quality/scale + state probes
fsr.resolveStatus() -- resolve success rate
fsr.log("...")      -- write to fsr-upsc-agent.log
```

Full picture of experimental toggles: `fsr2FgMvSign / fsr2FgMvScale / fsr2FgMult /
fsr2FgPace(N) / fsr2FgMvFlip / fsr2FgHudSnap / fsr2OutlineFix / fsr2OffSign /
setFsr2Debug` etc. are console-only (not persisted). Settled values are already
reflected in the code defaults.

Logging: `%USERPROFILE%\Zomboid\fsr-upsc-agent.log` and console.txt (with the
`[fsr-upsc]` tag). Logs are written only at session start and on failure; there is no
per-frame measurement output.

## Safety design

- Revision mismatch → no transformation at all, completely stock behavior
- If `RenderScale.disable()` fails, permanent stock fallback (reason logged)
- World pass only = UI/text/composite stay at full resolution
- GL on the render thread only; no GL query / allocation / logging in hook bodies
- The vision cone is excluded from scaling (optionally suppressed with the
  `fsrupsc-nocone` marker)

## Settled fix records

- **Outline offset fix (2026-10-26)**: when scaling, outlines of near targets
  (zombies/containers) displayed shrunk 0.775x (= 1488/1920) toward the upper left of
  the real image. Cause: the four glViewport calls in `ModelOutlines.endFrame` all used
  the same local rect, while fboA's mask had already been drawn in the same camera basis
  as the world pass (= region coordinates). Because the viewport funnel also narrowed
  the world-bound blit (:234) to the region size, the mask got a **second squeeze**
  (the deciding evidence was the shrink factor matching exactly one application of
  SCALE). Fix: `RenderScale.requestedViewport`'s `OUTLINE_VP_RAW` (code default ON)
  SKIPS the funnel rewrite on the entire endFrame seam (blit is fboA→world 1:1). Seam
  detection is `RenderScale.endFrameRawVp()` (stack scan). Reversible with
  `FsrControl.fsr2OutlineFix(0)`.
- **Composite flood fix**: `ResolveOutput.compositeTexture` returns the substituted
  texture only when gated by `inWindowThread()`. If the render thread passed the outline
  fboA/B/C `getTexture` while the window swap was open, the swap leaked and the frame
  flooded with the outline color.
- **Menu starvation false-positive fix (2026-10-24)**: the main menu background composite
  runs every frame without a world pass, so the `composite starved` counter (120 →
  session-wide disable) could be filled "at a stage where the world never existed" — a
  failure pattern where the whole mod stayed stock right after launch. Starvation
  counting is now restricted to after the resolve pipeline has run at least once.
  Includes console re-arming `FsrControl.fsrReenable()` after a fail-safe fires.
- **FG still gate settled (2026-10-24)**: interpolation's stop detection is only
  `CameraMotion.stillExact()` (d < 0.01px). The old loose threshold (0.5px) was the same
  order as walking deltas and caused full-screen vibration from dup freeze-jumps.
- **FG HUD snap (fsr2FgHudSnap, 2026-10-26)**: pixels of screen-static HUD whose content
  changed are replayed from the current copy alone in interpolated insertion frames
  (shader uFlags bit5, dz > 0.05 threshold, noise keeps the usual blending).

## Main components

- `src/fsrupsc/IsoViewProjection.java` — screen-space ortho VP composition
  (curr/prev/inv pairs for reprojection; `OFFSIGN` is flipped by
  `FsrControl.fsr2OffSign(±1)`)
- `src/fsrupsc/CameraMotion.java` — camera delta / still / zoom-change / resume-gap
  detection derived from `IsoCamera.frameState` (reset = MV clear + frameIndex=0)
- `src/fsrupsc/MotionField.java` + `MV_OVERRIDE_CS.glsl` — overwrites ObjectMotion entity
  rects into the camera MV (std140 UBO, render px units, `mv = dx*zoom*k`)
- `Fsr2Compute` — fsr2: 8 compute programs (MV_GEN/MV_OVERRIDE/RECONSTRUCT/
  DEPTH_CLIP/AUTO_REACTIVE/LOCK/ACCUMULATE/RCAS); fsr3: 9 (the fsr3 set +
  SHADING_CHANGE). Debug is `FsrControl.setFsr2Debug(n)` (3 = MV visualization)
- `src/fsrupsc/CameraJitter.java` — injects sub-pixel jitter into the world pass
  (Halton(2,3); read-modify-write of `IsoCamera.frameState` offX/offY, restored after
  the pass with a value-match check). Feeds the real jitter to ACCUMULATE de-jitter and
  LOCK `hrPosFromLrPos`. Default off, `FsrControl.fsr2Jitter(0/1)`. Stops injection
  automatically on compute fault.
- `src/fsrupsc/DepthProxy.java` + `DEPTH_PROXY_CS.glsl` — probes the world FBO's depth
  attachment (type/format, logged once) → `glBlitFramebuffer(DEPTH)` into a own-format
  depth FBO → converted to an R32F inverted-depth map (`viewZ = 1/d`, background = far)
  and promotes `uUseDepth=1` for RECONSTRUCT/DEPTH_CLIP/ACCUMULATE. Default off,
  `FsrControl.fsr2Depth(0/1/2)` (1 = auto, 2 = force).
- `AUTO_REACTIVE_CS.glsl` + `Fsr2Compute` M3 integration — auto-reactive: detects
  screen-space overlays (outline/view cone/particles/attack highlights etc., gaze-point
  systems outside MV coverage) via the luma difference between current color and the
  prev color at prevUv (from the MV field) and grants fresh-sample weight. A faithful
  port of FSR4's composite MV_GEN T&C spec. Prev color is captured at dispatch end via
  `glCopyImageSubData(colorTex→prevColorTex)`. Default ON, `FsrControl.fsr2AutoReactive(0/1)`.
- M3.1 stop reset — a single transition from moving to `stillExact` (<0.01px) sets
  `currentFrameIndex=0` (re-converges on the static position basis).
- M3.2 `fsr2CamReactive(0-5)` — attenuates motion smear (a port of fsr-reloaded's
  `uCamReactive` floor). Default 1 (mild), 0 disables.
- `42/media/shaders/fsr3/*.glsl` + `Fsr3Shaders.java` + `Fsr2Compute` M4 integration —
  the fsr3 mode implementation: borrows the mod's fsr3 shader set (9 programs including
  SHADING_CHANGE) and inserts the 8x8 tile luma-ratio diff SHADING_CHANGE pass in
  dispatch order MV → SHADING_CHANGE → RECONSTRUCT. The M3 auto-reactive is also
  ported into fsr3 DEPTH_CLIP.
- `src/fsrupsc/Fsr2Shaders.java` — embeds the AMD FidelityFX FSR2 compute shaders +
  MV_OVERRIDE (`42/media/shaders/fsr2/*.glsl` is the upstream mirror, regenerated by
  `scripts/gen-shaders.ps1`)
- `Upscaler`: `fsr2`/`fsr3` modes run Fsr2Compute dispatch via `runResolve` when compute
  is available, and on failure rescue that frame with fsr1 (a compute fault is
  session-limited; fsr1 always stays alive)
- `42/media/shaders/fsr_blit.vert + fsr_easu.frag + fsr_rcas.frag` — FSR 1's spatial
  upscaling trio (the fallback path)
- `src/fsrupsc/FgPresent.java` — software frame generation (ping-pong copy retention,
  duplicate/interpolated insertion, HUD snap, VSync-off pacing, fail-safe strike control)

## License / third-party libraries

See `THIRD-PARTY-NOTICES.md` (AMD FidelityFX GLSL origin, fsr-reloaded origin,
byte-buddy Apache-2.0. Frame generation is our own implementation and bundles no
third-party code).

## Development records

The development-era diagnostic process (triage procedures and measurements for the
outline offset, FG vibration, flicker, etc.) is in `docs/DIAGNOSIS-HISTORY.md`;
Phase 2 design is in `docs/PHASE2-FSR3.md` / `docs/PHASE2-FSR3-GLSLDESIGN.md`.
