FSR-UPSC agent jar.

Build it with: ..\..\..\scripts\build-agent.ps1
  - compiles src\fsrupsc against the game jar
  - checks zombie/GitVersion REVISION == 4a0e9546ec (NOT "42.21")
  - outputs: fsr-upsc-agent.jar (copy that file into this folder)

Install it with: ..\..\..\scripts\install-agent.ps1 -GameDir "E:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid"
  - copies fsr-upsc-agent.jar next to projectzomboid.jar
  - adds "-javaagent:fsr-upsc-agent.jar" + "--enable-native-access=ALL-UNNAMED"
    to ProjectZomboid64.json (backs it up once)
  - NO other properties needed: hook mask defaults to all groups (incl.
    "present") and the downscale master switch defaults ON
  - optional overrides:
      -Hooks '<groups>'           restrict the hook mask
      -NoDownscale                disable the world downscale path
      (or without the script: env FSRUPSC_DOWNSCALE / marker file
       Zomboid\fsr-upsc-downscale / -Dfsrupsc.downscale, same for hooks)

Revision rule: always match 4a0e9546ec. A jar built for another revision must
refuse to transform (FsrAgent checks at premain; the game runs stock).

Software frame generation (test, Phase 2):
  - Lua console: FsrControl.fsr2FgTest(0|1|2|3)
      0 off / 1 magenta insert / 2 duplicate insert / 3 interpolation
      (per-pixel MV field when fsr2/fsr3 compute is live, camera shift fallback)
  - Direction mirror: FsrControl.fsr2FgMvSign(-1|1)
  - Requires vsync ON: the loop halves (the in-game FPS counter reading ~half
    is expected; the display shows one inserted frame per real frame)
  - VSync OFF variant (experimental): FsrControl.fsr2FgPace(0|-1|N)
      0 off (default) / -1 auto (one display refresh) / N = manual exposure
      in tenths of a ms (167 ~ 60Hz, 69 ~ 144Hz). Paces the loop at ~2x the
      exposure per real present so both frames get scanout; boundary tearing
      is possible since scanout alignment is not controlled.
  - Known limits on the inserted frame: UI is one frame old; a mismatch-
    weighted blend leans toward the current frame, so moving entities can
    still smear slightly at high speeds.
