FSR-UPSC agent jars, one folder per game build (javaagent mode):

  42.21/fsr-upsc-agent.jar   game revision 4a0e9546ec (Build 42.21)
                             legacy build for rollback players.
                             Javaagent mode only: on 42.21 the ZombieBuddy
                             mode of this mod runs stock.

  42.22/fsr-upsc-agent.jar   game revision e013dbb529 (Build 42.22)
                             the unified build. Serves the -javaagent
                             launch option; the byte-identical copy at
                             ..\..\media\java\fsr-upsc-agent.jar is what
                             the ZombieBuddy java-mod load reads from
                             mod.info (javaJarFile/javaPkgName).

Every jar carries a fsrupsc/build-info.properties entry (revision=<hash>)
plus Target-Revision in its manifest, so PZ JAM Launcher's version-check
gate can verify it against the installed game. An agent built for another
revision refuses to transform and the game runs stock.

Rollback flow (42.22 -> 42.21), javaagent mode:

  powershell -ExecutionPolicy Bypass -File scripts\install-agent.ps1 -VersionLabel 42.21

The script also auto-detects the installed game build and picks the matching
jar without -VersionLabel. Going back to 42.22: run the script again (no
arguments) or just enable the mod with ZombieBuddy.

No scripts folder available (plain Workshop copy)? Copy the jar anywhere
permanent (e.g. C:\PZAgents) and add two lines to the vmArgs array in
ProjectZomboid64.json (game folder; back the file up first):

  "-javaagent:C:/PZAgents/fsr-upsc-agent.jar",
  "--enable-native-access=ALL-UNNAMED"

Or point PZ-JAM-Launcher at the jar, which manages the same two options.
The same two lines work for the 42.21 jar - just change the path.
