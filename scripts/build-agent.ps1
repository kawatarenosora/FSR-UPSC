<#
.SYNOPSIS
Build fsr-upsc-agent.jar against game revision 4a0e9546ec.
.DESCRIPTION
Compiles src/agent + src/fsrupsc with javac (JDK 25+) against
<game>/projectzomboid.jar, verifies zombie/GitVersion REVISION and
drops 42/agent/fsr-upsc-agent.jar. Use the hash, never the "42.21" label.
#>
param(
  [string]$GameDir = "E:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid",
  [string]$Revision = "4a0e9546ec"
)
$ErrorActionPreference = 'Stop'
$Jar = Join-Path $GameDir 'projectzomboid.jar'
if (-not (Test-Path -LiteralPath $Jar)) { throw "no projectzomboid.jar in $GameDir" }
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
$AgentJar = Join-Path $Root '42\agent\fsr-upsc-agent.jar'
Remove-Item -Recurse -Force $Out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Out | Out-Null
$Sources = @(Get-ChildItem -LiteralPath (Join-Path $Root 'src') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
$BbDir = Join-Path $Root 'vendor\lib'
$Bb = @(Get-ChildItem -LiteralPath $BbDir -Filter 'byte-buddy*.jar' | ForEach-Object { $_.FullName })
if ($Bb.Count -eq 0) { throw "no byte-buddy jars in $BbDir" }
& $Javac -encoding UTF-8 -cp ($Jar + ';' + ($Bb -join ';')) -d $Out $Sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
# Keep the mod-folder copy (used by PZ JAM Launcher) in sync when present.
$ModJar = Join-Path $env:USERPROFILE 'Zomboid\mods\FSR-UPSC\42\agent\fsr-upsc-agent.jar'
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
if ($m.Value -ne $Revision) { throw "game is $($m.Value), expected $Revision (pass -Revision to override)" }
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

# javaagent JAR needs Premain-Class in MANIFEST.MF, otherwise the JVM aborts
# at startup ("Failed to find Premain-Class manifest attribute") and the game
# crashes on load. Revision attributes let external loaders read rev=? away.
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
Write-Output "built $AgentJar for game revision $Revision (byte-buddy unpacked: $($Bb.Count) jars)"
