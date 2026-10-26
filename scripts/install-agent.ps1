<#
.SYNOPSIS
Install fsr-upsc-agent.jar into the game (adds -javaagent).
.DESCRIPTION
Copies 42/agent/fsr-upsc-agent.jar next to projectzomboid.jar and adds
"-javaagent:fsr-upsc-agent.jar" + "--enable-native-access=ALL-UNNAMED"
to ProjectZomboid64.json vmArgs (backs up once to *.fsrupsc-bak).
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
  # Optional overrides written into ProjectZomboid64.json vmArgs.
  #   -Hooks all|none|loop,internal,fbo,draw,viewport,present
  #   -NoDownscale         disable the world downscale path (default ON)
  [string]$Hooks = '',
  [switch]$NoDownscale
)
$ErrorActionPreference = 'Stop'
$Jar = Join-Path $GameDir 'projectzomboid.jar'
$Json = Join-Path $GameDir 'ProjectZomboid64.json'
$AgentSrc = Join-Path (Split-Path -Parent $PSScriptRoot) '42\agent\fsr-upsc-agent.jar'
$AgentDst = Join-Path $GameDir 'fsr-upsc-agent.jar'
$Bak = "$Json.fsrupsc-bak"

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
