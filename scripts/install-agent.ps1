<#
.SYNOPSIS
Install fsr-upsc-agent.jar into the game (adds -javaagent).
.DESCRIPTION
Copies the agent jar next to projectzomboid.jar and adds
"-javaagent:fsr-upsc-agent.jar" + "--enable-native-access=ALL-UNNAMED"
to ProjectZomboid64.json vmArgs (backs up once to *.fsrupsc-bak).

Source jar selection (auto, override with -VersionLabel 42.21 / 42.22):
- 42\agent\<build>\fsr-upsc-agent.jar - one jar per game build
  (42.22 = e013dbb529, the unified build; 42.21 = 4a0e9546ec, the legacy
  rollback build, javaagent mode only). The 42.22 jar is mirrored
  byte-identical at 42\media\java\fsr-upsc-agent.jar, the copy the
  ZombieBuddy java-mod load reads via mod.info. (javaagent mode only)

Every jar is revision-gated inside (build-info.properties / Target-Revision
vs zombie.GitVersion) and runs stock on a mismatched game build, so a wrong
pick degrades to stock rendering instead of misbehaving.
No FSR-UPSC -D properties are written by default: the hook mask defaults
to all groups and the world-downscale master switch defaults ON inside
the agent, so a bare -javaagent is a complete install. The optional
switches below write properties only when you pass them.
Game must be closed. Uninstall removes the two vmArgs + the jar.
The unified jar also loads without this script when ZombieBuddy is
installed: enable the mod and ZB boots it as a java mod (see README).
#>
param(
  [string]$GameDir = "E:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid",
  [switch]$Uninstall,
  [switch]$Detach,
  [switch]$Status,
  # Accepted for compatibility; the single media/java jar replaced the
  # per-build agent\<label> folders.
  [string]$VersionLabel = '',
  # Optional overrides written into ProjectZomboid64.json vmArgs.
  #   -Hooks all|none|loop,internal,fbo,draw,viewport,present
  #   -NoDownscale         disable the world downscale path (default ON)
  [string]$Hooks = '',
  [switch]$NoDownscale
)
$ErrorActionPreference = 'Stop'
$Jar = Join-Path $GameDir 'projectzomboid.jar'
$Json = Join-Path $GameDir 'ProjectZomboid64.json'
$Bak = "$Json.fsrupsc-bak"
# Source selection: one jar per game build under 42\agent\<label> (the
# 42.22 jar is the unified build, mirrored byte-identical at 42\media\java
# for the ZombieBuddy load path). -VersionLabel overrides the
# auto-detection (42.21 / 42.22).
$AgentRoot = Split-Path -Parent $PSScriptRoot
$AgentSrcLabels = @{
  '42.21' = Join-Path $AgentRoot "42\agent\42.21\fsr-upsc-agent.jar"
  '42.22' = Join-Path $AgentRoot "42\agent\42.22\fsr-upsc-agent.jar"
}
if (-not $VersionLabel) {
  if (Test-Path -LiteralPath $Jar) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $z = [System.IO.Compression.ZipFile]::OpenRead($Jar)
    try {
      $e = $z.GetEntry('zombie/GitVersion.class')
      if ($e) {
        $s = $e.Open(); $ms = New-Object System.IO.MemoryStream; $s.CopyTo($ms); $s.Dispose()
        $txt = [System.Text.Encoding]::ASCII.GetString($ms.ToArray())
        $rev = [regex]::Match($txt, '\b[0-9a-f]{10}\b').Value
        if ($rev -eq '4a0e9546ec') { $VersionLabel = '42.21' }
        elseif ($rev -eq 'e013dbb529') { $VersionLabel = '42.22' }
      }
    } finally { $z.Dispose() }
  }
}
if (-not $VersionLabel) {
  throw "cannot detect the game build from $Jar - pass -VersionLabel 42.21 / 42.22"
}
if (-not $AgentSrcLabels.ContainsKey($VersionLabel)) {
  throw "no bundled jar for $VersionLabel (supported: 42.21 / 42.22)"
}
$AgentSrc = $AgentSrcLabels[$VersionLabel]
$AgentDst = Join-Path $GameDir 'fsr-upsc-agent.jar'

if ($Status) {
  Write-Output "game dir: $GameDir"
  if (Test-Path -LiteralPath $Bak) { Write-Output 'backup: yes' } else { Write-Output 'backup: none' }
  if (Test-Path -LiteralPath $Json) {
    $j = Get-Content -LiteralPath $Json -Raw | ConvertFrom-Json
    Write-Output ("vmArgs has javaagent: " + (@($j.vmArgs) -like '-javaagent:*').Count)
  }
  Write-Output ("agent jar present: " + (Test-Path -LiteralPath $AgentDst))
  exit 0
}
if (-not (Test-Path -LiteralPath $Json)) { throw "no ProjectZomboid64.json in $GameDir" }
# Detach: drop only the vmArgs, keep the jar. Lets a plain stock launch
# prove whether the agent is implicated at all. Re-attach = run without
# -Detach (the install path below appends the args back).
if ($Detach) {
  $running = Get-Process ProjectZomboid64 -ErrorAction SilentlyContinue
  if ($running) { throw 'close the game first' }
  $j = Get-Content -LiteralPath $Json -Raw | ConvertFrom-Json
  $j.vmArgs = @($j.vmArgs | Where-Object { $_ -notlike '-javaagent:fsr-upsc*' -and $_ -ne '--enable-native-access=ALL-UNNAMED' })
  [IO.File]::WriteAllText($Json, ($j | ConvertTo-Json -Depth 10), (New-Object Text.UTF8Encoding $false))
  Write-Output 'detached agent vmArgs (jar kept); launch the game stock'
  exit 0
}
if ($Uninstall) {
  $j = Get-Content -LiteralPath $Json -Raw | ConvertFrom-Json
  $j.vmArgs = @($j.vmArgs | Where-Object { $_ -notlike '-javaagent:fsr-upsc*' -and $_ -ne '--enable-native-access=ALL-UNNAMED' })
  [IO.File]::WriteAllText($Json, ($j | ConvertTo-Json -Depth 10), (New-Object Text.UTF8Encoding $false))
  if (Test-Path -LiteralPath $AgentDst) { Remove-Item -LiteralPath $AgentDst -Force }
  Write-Output 'uninstalled agent vmArgs + jar'
  exit 0
}
$running = Get-Process ProjectZomboid64 -ErrorAction SilentlyContinue
if ($running) { throw 'close the game first' }
if (-not (Test-Path -LiteralPath $AgentSrc)) { throw "build first: missing $AgentSrc" }
if (-not (Test-Path -LiteralPath $Bak)) { Copy-Item -LiteralPath $Json -Destination $Bak }
Copy-Item -LiteralPath $AgentSrc -Destination $AgentDst -Force
$j = Get-Content -LiteralPath $Json -Raw | ConvertFrom-Json
# Rebuild only our own entries; leave any user vmArgs untouched.
$keep = @($j.vmArgs | Where-Object {
  $_ -notlike '-javaagent:fsr-upsc*' -and
  $_ -ne '--enable-native-access=ALL-UNNAMED' -and
  $_ -notlike '-Dfsrupsc.*' -and
  $_ -notlike '-Dfsrupsc*'
})
$ours = @(
  '-javaagent:fsr-upsc-agent.jar',
  '--enable-native-access=ALL-UNNAMED'
)
if ($Hooks)      { $ours += "-Dfsrupsc.hooks=$Hooks" }
if ($NoDownscale){ $ours += '-Dfsrupsc.downscale=false' }
$j.vmArgs = @($keep) + @($ours)
[IO.File]::WriteAllText($Json, ($j | ConvertTo-Json -Depth 10), (New-Object Text.UTF8Encoding $false))
Write-Output "installed $AgentDst + vmArgs (hooks=$(if($Hooks){$Hooks}else{'default'}) noDownscale=$($NoDownscale.IsPresent)); restart from Steam"
