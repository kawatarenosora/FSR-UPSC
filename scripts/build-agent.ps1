<#
.SYNOPSIS
Build fsr-upsc-agent.jar against the installed game revision.
.DESCRIPTION
Compiles src/agent + src/fsrupsc with javac (JDK 25+) against
<game>/projectzomboid.jar, verifies zombie/GitVersion REVISION and
drops 42/agent/<label>/fsr-upsc-agent.jar, where <label> is the game
build folder (42.21 / 42.22) derived from the revision hash. Use the
hash, never the "42.x" label, for revision checks.
#>
param(
  [string]$GameDir = "E:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid",
  [string]$Revision = "",               # defaults to the installed game's revision
  [string]$VersionLabel = ""            # override the agent/<label>/ folder name
)
$ErrorActionPreference = 'Stop'
$Jar = Join-Path $GameDir 'projectzomboid.jar'
if (-not (Test-Path -LiteralPath $Jar)) { throw "no projectzomboid.jar in $GameDir" }
# Revision -> build label for the agent/<label>/ layout. The revision hash is
# authoritative; labels only name the folder the matching jar lives in.
$RevLabels = @{ '4a0e9546ec' = '42.21'; 'e013dbb529' = '42.22' }
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
if (-not $VersionLabel) {
  if (-not $RevLabels.ContainsKey($Revision)) {
    throw "unknown revision $Revision - pass -VersionLabel <build folder, e.g. 42.23>"
  }
  $VersionLabel = $RevLabels[$Revision]
}
$Label = $VersionLabel
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
# The agent's own revision check reads fsrupsc/build-info.properties (below)
# at runtime via fsrupsc.AgentRev; no constants to stamp in the source.
$Out = Join-Path $Root 'build\agent-classes'
$AgentDir = Join-Path $Root "42\agent\$Label"
$AgentJar = Join-Path $AgentDir 'fsr-upsc-agent.jar'
Remove-Item -Recurse -Force $Out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Out | Out-Null
$Sources = @(Get-ChildItem -LiteralPath (Join-Path $Root 'src') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
$BbDir = Join-Path $Root 'vendor\lib'
$Bb = @(Get-ChildItem -LiteralPath $BbDir -Filter 'byte-buddy*.jar' | ForEach-Object { $_.FullName })
if ($Bb.Count -eq 0) { throw "no byte-buddy jars in $BbDir" }
& $Javac -encoding UTF-8 -cp ($Jar + ';' + ($Bb -join ';')) -d $Out $Sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
# Keep the local copies in sync when present: the WIP copy under
# Zomboid\mods and the Workshop staging copy (upload source) under
# Zomboid\Workshop\FSR-UPSC.
$ModJar = Join-Path $env:USERPROFILE "Zomboid\mods\FSR-UPSC\42\agent\$Label\fsr-upsc-agent.jar"
$WsJar = Join-Path (Join-Path $env:USERPROFILE 'Zomboid\Workshop\FSR-UPSC\Contents\mods\FSR-UPSC\42\agent') "$Label\fsr-upsc-agent.jar"
# revision check: read zombie/GitVersion.class constant-pool ASCII
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
  # Cross-build jar (e.g. rebuilding the 42.21 jar once the disk has moved to
  # 42.22). Safe: the target revision is passed explicitly, goes into
  # build-info.properties and the agent/launcher gates on it everywhere.
  Write-Warning "game is $($m.Value), building -Revision $Revision (cross-build)"
}
if (Test-Path -LiteralPath $AgentJar) { Remove-Item -LiteralPath $AgentJar -Force }
# Unpack byte-buddy into the classes dir so the agent jar is self-contained
# (unshaded net.bytebuddy: Z-UET shades to zuet.shade, so no conflict).
Push-Location -LiteralPath $Out
try {
  foreach ($b in $Bb) {
    & $JarTool --extract --file $b
    if ($LASTEXITCODE -ne 0) { throw "jar extract failed: $b" }
  }
} finally {
  Pop-Location
}

# launcher rev check (Javamod.ReadAgentRevision) greps a build-info.properties
# entry inside the jar; without it the launcher reports rev unknown.
Set-Content -LiteralPath (Join-Path $Out 'fsrupsc\build-info.properties') `
  -Value "revision=$Revision" -Encoding ASCII

# javaagent JAR needs Premain-Class in MANIFEST.MF, otherwise the JVM aborts
# at startup ("Failed to find Premain-Class manifest attribute") and the game
# crashes on load. Revision attributes + public/revision in build-info.properties
# let external loaders (PZ JAM Launcher) gate agents by game rev.
$Manifest = Join-Path ([IO.Path]::GetTempPath()) 'fsr-upsc-manifest.txt'
@(
  'Manifest-Version: 1.0',
  'Premain-Class: fsrupsc.FsrAgent',
  'Can-Redefine-Classes: true',
  'Can-Retransform-Classes: true',
  'Implementation-Title: fsr-upsc-agent',
  "Implementation-Version: $Revision",
  "Target-Revision: $Revision",
  ''
) | Set-Content -LiteralPath $Manifest -Encoding ASCII
& $JarTool --create --file $AgentJar --manifest $Manifest -C $Out .
Remove-Item -LiteralPath $Manifest -Force -ErrorAction SilentlyContinue
if (Test-Path -LiteralPath $ModJar) {
  Copy-Item -LiteralPath $AgentJar -Destination $ModJar -Force
  Write-Output "synced $ModJar"
} else {
  Write-Output "note: mod-folder copy absent ($ModJar), skipped sync"
}
if (Test-Path -LiteralPath $WsJar) {
  Copy-Item -LiteralPath $AgentJar -Destination $WsJar -Force
  Write-Output "synced $WsJar"
}
Write-Output "built $AgentJar for game revision $Revision (byte-buddy unpacked: $($Bb.Count) jars)"