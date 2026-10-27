<#
.SYNOPSIS
Install fsr-upsc-agent.jar into the game (adds -javaagent).
.DESCRIPTION
Copies 42/agent/<label>/fsr-upsc-agent.jar next to projectzomboid.jar and adds
"-javaagent:fsr-upsc-agent.jar" + "--enable-native-access=ALL-UNNAMED"
to ProjectZomboid64.json vmArgs (backs up once to *.fsrupsc-bak). <label>
is the game build folder (42.21 / 42.22) auto-detected from the game jar's
revision hash, so the installed jar always matches the installed game.
No FSR-UPSC -D properties are written by default: the hook mask defaults
to all groups and the world-downscale master switch defaults ON inside
the agent, so a bare -javaagent is a complete install. The optional
switches below write properties only when you pass them.
Game must be closed. Uninstall removes the two vmArgs + the jar.
#>
param(
  [string]$GameDir = "E:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid",
  [switch]$Uninstall,
  [switch]$Detach,
  [switch]$Status,
  # Optional build-folder override (42.21 / 42.22). Normally auto-detected
  # from the game jar's revision hash, same mapping as build-agent.ps1.
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
# Pick the agent folder matching the installed game: read the revision hash
# from projectzomboid.jar and map it like build-agent.ps1. Passing
# -VersionLabel 42.21 / 42.22 overrides the detection.
$RevLabels = @{ '4a0e9546ec' = '42.21'; 'e013dbb529' = '42.22' }
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
        if ($rev -and $RevLabels.ContainsKey($rev)) { $VersionLabel = $RevLabels[$rev] }
      }
    } finally { $z.Dispose() }
  }
}
if (-not $VersionLabel) {
  throw "cannot detect the game build from $Jar - pass -VersionLabel 42.21 / 42.22"
}
$AgentSrc = Join-Path (Split-Path -Parent $PSScriptRoot) "42\agent\$VersionLabel\fsr-upsc-agent.jar"
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
