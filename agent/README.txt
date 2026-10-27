FSR-UPSC agent jars, one folder per game build:
  42.21/fsr-upsc-agent.jar   game revision 4a0e9546ec (Build 42.21)
  42.22/fsr-upsc-agent.jar   game revision e013dbb529 (Build 42.22)

Each jar carries a fsrupsc/build-info.properties entry (revision=<hash>) plus
Target-Revision in its manifest, so PZ JAM Launcher's version-check gate can
verify it against the installed game. An agent built for another revision
refuses to transform at premain and the game runs stock.

Build it with: ..\scripts\build-agent.ps1
  - compiles src\fsrupsc against <game>\projectzomboid.jar
  - reads the game revision from zombie/GitVersion (the hash, never "42.x")
    and drops the jar into agent\<label>\ (42.21 / 42.22; override the
    folder name with -VersionLabel)
  - the jar includes build-info.properties + manifest rev attributes

Install it with: ..\scripts\install-agent.ps1
  - auto-detects the installed game build the same way and copies that
    folder's fsr-upsc-agent.jar next to projectzomboid.jar
    (override with -VersionLabel 42.21 / 42.22)
  - adds "-javaagent:fsr-upsc-agent.jar" + "--enable-native-access=ALL-UNNAMED"
    to ProjectZomboid64.json (backs it up once)
  - NO other properties needed: hook mask defaults to all groups (incl.
    "present") and the downscale master switch defaults ON
  - optional overrides:
      -Hooks '<groups>'           restrict the hook mask
      -NoDownscale                disable the world downscale path
      (or without the script: env FSRUPSC_DOWNSCALE / marker file
       Zomboid\fsr-upsc-downscale / -Dfsrupsc.downscale, same for hooks)

Software frame generation (FgPresent):
  - Lua console: FsrControl.fsr2FgTest(0|2|3)
      0 off / 2 duplicate insert (recommended, stable) / 3 interpolation
      (per-pixel MV field when fsr2/fsr3 compute is live, camera shift
      fallback, duplication as last resort)
  - Direction mirror: FsrControl.fsr2FgMvSign(-1|1)
  - Pacing pairs one-for-one with the VSync setting (exactly one pacer):
      fsr2FgPace(0)  + VSync ON   = VSync paces the loop (default, stable,
        no tearing; the FPS counter reading ~half the display rate is
        expected, the display shows one inserted frame per real frame)
      fsr2FgPace(-1) + VSync OFF  = mod-paced full-refresh exposure,
        same cadence as off, no tearing
      fsr2FgPace(-2) + VSync OFF  = mod-paced half-refresh exposure,
        higher cadence, occasional tearing/flicker possible
  - Known limits on the inserted frame: UI is one frame old; a mismatch-
    weighted blend leans toward the current frame, so moving entities can
    still smear slightly at high speeds.
