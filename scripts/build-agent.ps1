<#
.SYNOPSIS
Build the ZombieBuddy-edition fsr-upsc-agent.jar against the installed game revision.
.DESCRIPTION
Compiles src/fsrupsc with javac (JDK 25+) against <game>/projectzomboid.jar,
verifies zombie/GitVersion REVISION and drops 42/media/java/fsr-upsc-agent.jar.

ZombieBuddy edition specifics:
- The jar does NOT bundle byte-buddy. It compiles against vendor/lib
  byte-buddy 1.18.8 (exactly what ZombieBuddy 2.x ships) and resolves
  byte-buddy from ZombieBuddy's own agent jar at runtime (ZombieBuddy 3.x
  ships 1.18.14; same 1.18.x API family). One jar works on both editions.
- The manifest has no Premain-Class on purpose: this jar is loaded by
  ZombieBuddy from mod.info (fsrupsc.Main), never as a -javaagent.
- One jar per game revision: the target revision goes into
  fsrupsc/build-info.properties and AgentRev gates the hooks on it.
  When the game updates, rebuild with the new revision and publish a mod
  update; mismatched revisions run stock by design.
#>
param(
  [string]$GameDir = "E:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid",
  [string]$Revision = "",               # defaults to the installed game's revision
  [string]$VersionLabel = ""            # accepted for compatibility; ignored (single media/java output)
)
$ErrorActionPreference = 'Stop'
$Jar = Join-Path $GameDir 'projectzomboid.jar'
if (-not (Test-Path -LiteralPath $Jar)) { throw "no projectzomboid.jar in $GameDir" }
# The revision hash is authoritative; "42.x" labels name nothing here because
# the jar lives in a single media/java path (ZombieBuddy reads one javaJarFile).
if (-not $Revision) {
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $z = [System.IO.Compression.ZipFile]::OpenRead($Jar)
  try {
    $e = $z.GetEntry('zombie/GitVersion.class')
    if (-not $e) { throw 'no zombie/GitVersion.class in jar' }
    $s = $e.Open(); $ms = New-Object System.IO.MemoryStream; $s.CopyTo($ms); $s.Dispose()
    $txt = [System.Text.Encoding]::ASCII.GetString($ms.ToArray())
  } finally { $z.Dispose() }
  $m = [regex]::Match($txt, '\b[0-9a-f]{10}\b')
  if (-not $m.Success) { throw "could not read game revision (pass -Revision)" }
  $Revision = $m.Value
}
# Locate a JDK 25+ (javac/jar), PATH first, then common install roots.
function Resolve-JdkTool([string]$tool) {
  $cmd = Get-Command $tool -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }
  foreach ($root in @('C:\Program Files\Java', 'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Microsoft')) {
    if (Test-Path -LiteralPath $root) {
      $hit = Get-ChildItem -LiteralPath $root -Recurse -Filter "$tool.exe" -ErrorAction SilentlyContinue |
             Sort-Object FullName -Descending | Select-Object -First 1
      if ($hit) { return $hit.FullName }
    }
  }
  throw "$tool not found (need JDK 25+ on PATH or under C:\Program Files\Java)"
}
$Javac = Resolve-JdkTool 'javac'
$JarTool = Resolve-JdkTool 'jar'
$Root = Split-Path -Parent $PSScriptRoot
$Out = Join-Path $Root 'build\agent-classes'
$AgentJar = Join-Path $Root '42\media\java\fsr-upsc-agent.jar'
Remove-Item -Recurse -Force $Out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Out | Out-Null
# Compile against the game jar + vendor byte-buddy (1.18.8). At runtime
# byte-buddy resolves from ZombieBuddy's jar; compiling against the 2.x
# version keeps the API subset both editions serve.
$BbDir = Join-Path $Root 'vendor\lib'
$Bb = @(Get-ChildItem -LiteralPath $BbDir -Filter 'byte-buddy*.jar' | ForEach-Object { $_.FullName })
if ($Bb.Count -eq 0) { throw "no byte-buddy jars in $BbDir" }
$Sources = @(Get-ChildItem -LiteralPath (Join-Path $Root 'src') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
& $Javac -encoding UTF-8 -cp ($Jar + ';' + ($Bb -join ';')) -d $Out $Sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
# Revision check against the game on disk; cross-builds (e.g. rebuilding the
# 42.21 jar after the disk moved to 42.22) are allowed with a warning. Safe:
# the target revision goes into build-info.properties and AgentRev refuses to
# arm on a mismatched game.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [System.IO.Compression.ZipFile]::OpenRead($Jar)
try {
  $e = $z.GetEntry('zombie/GitVersion.class')
  if (-not $e) { throw 'no zombie/GitVersion.class in jar' }
  $s = $e.Open(); $ms = New-Object System.IO.MemoryStream; $s.CopyTo($ms); $s.Dispose()
  $txt = [System.Text.Encoding]::ASCII.GetString($ms.ToArray())
} finally { $z.Dispose() }
$m = [regex]::Match($txt, '\b[0-9a-f]{10}\b')
if (-not $m.Success) { throw 'could not read game revision' }
if ($m.Value -ne $Revision) {
  Write-Warning "game is $($m.Value), building -Revision $Revision (cross-build)"
}
# The agent's own revision check reads fsrupsc/build-info.properties at
# runtime via fsrupsc.AgentRev; external loaders grep the same entry.
Set-Content -LiteralPath (Join-Path $Out 'fsrupsc\build-info.properties') `
  -Value "revision=$Revision" -Encoding ASCII
# NO Premain-Class on purpose: ZombieBuddy loads this jar from mod.info and
# runs fsrupsc.Main. Without the attribute the jar cannot be (mis)used as a
# -javaagent, which would fail anyway (byte-buddy is not bundled).
$Manifest = Join-Path ([IO.Path]::GetTempPath()) 'fsr-upsc-manifest.txt'
@(
  'Manifest-Version: 1.0',
  'Implementation-Title: fsr-upsc-agent-zb',
  "Implementation-Version: $Revision",
  "Target-Revision: $Revision",
  ''
) | Set-Content -LiteralPath $Manifest -Encoding ASCII
if (Test-Path -LiteralPath $AgentJar) { Remove-Item -LiteralPath $AgentJar -Force }
& $JarTool --create --file $AgentJar --manifest $Manifest -C $Out .
Remove-Item -LiteralPath $Manifest -Force -ErrorAction SilentlyContinue
# Keep the local copies in sync when present: the WIP test mod under
# Zomboid\mods\FSR-UPSC-ZB and the (future) Workshop staging copy under
# Zomboid\Workshop\FSR-UPSC-ZB.
$ModJar = Join-Path $env:USERPROFILE "Zomboid\mods\FSR-UPSC-ZB\42\media\java\fsr-upsc-agent.jar"
$WsJar = Join-Path $env:USERPROFILE "Zomboid\Workshop\FSR-UPSC-ZB\Contents\mods\FSR-UPSC\42\media\java\fsr-upsc-agent.jar"
if (Test-Path -LiteralPath $ModJar) {
  Copy-Item -LiteralPath $AgentJar -Destination $ModJar -Force
  Write-Output "synced $ModJar"
} else {
  Write-Output "note: WIP mod copy absent ($ModJar), skipped sync"
}
if (Test-Path -LiteralPath $WsJar) {
  Copy-Item -LiteralPath $AgentJar -Destination $WsJar -Force
  Write-Output "synced $WsJar"
}
Write-Output "built $AgentJar for game revision $Revision (byte-buddy resolved from ZombieBuddy, not bundled)"
