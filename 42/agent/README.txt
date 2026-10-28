FSR-UPSC agent jars by game build:

  42.21/fsr-upsc-agent.jar   game revision 4a0e9546ec (Build 42.21)
                             legacy javaagent build for rollback players.
                             Javaagent mode only: ZombieBuddy does not load
                             this folder - on 42.21 the ZombieBuddy mode of
                             this mod runs stock.

The current unified build lives one level up, at
..\..\media\java\fsr-upsc-agent.jar (game revision e013dbb529, Build 42.22).
It serves BOTH loading modes: the -javaagent launch option and the
ZombieBuddy java-mod load (javaJarFile/javaPkgName in mod.info).

Every jar carries a fsrupsc/build-info.properties entry (revision=<hash>)
plus Target-Revision in its manifest, so PZ JAM Launcher's version-check
gate can verify it against the installed game. An agent built for another
revision refuses to transform and the game runs stock.

Rollback flow (42.22 -> 42.21), javaagent mode:

  powershell -ExecutionPolicy Bypass -File scripts\install-agent.ps1 -VersionLabel 42.21

The script also auto-detects the installed game build and picks the matching
jar without -VersionLabel. Going back to 42.22: run the script again (no
arguments) or just enable the mod with ZombieBuddy.
