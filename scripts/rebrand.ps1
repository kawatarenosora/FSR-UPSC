# One-shot rebrand: FSR-UPSC / fsrupsc* -> FSR-UPSC / fsrupsc (fully original namespace).
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$exts = @('.java','.lua','.json','.md','.txt','.ps1','.info','.cpp','.vert','.frag')
$files = Get-ChildItem -LiteralPath $Root -Recurse -File |
  Where-Object { $exts -contains $_.Extension -and $_.FullName -notmatch '\\build\\' }

foreach ($f in $files) {
  $raw = [IO.File]::ReadAllText($f.FullName)
  $t = $raw
  # markers first (more specific before general)
  $t = $t -creplace 'fsrupsc-nocone1',   'fsrupsc-nocone1'
  $t = $t -creplace 'fsrupsc-nocone2',   'fsrupsc-nocone2'
  $t = $t -creplace 'fsrupsc-nocone',    'fsrupsc-nocone'
  $t = $t -creplace 'fsrupsc-coneviewport', 'fsrupsc-coneviewport'
  $t = $t -creplace 'fsrupsc-camera-scale', 'fsrupsc-camera-scale'
  $t = $t -creplace 'fsr-upsc-agent\.jar', 'fsr-upsc-agent.jar'
  $t = $t -creplace 'FSR-UPSC',  'FSR-UPSC'
  $t = $t -creplace 'FSR_UPSC',  'FSR_UPSC'
  $t = $t -creplace 'FSRUPSC_DOWNSCALE', 'FSRUPSC_DOWNSCALE'
  $t = $t -creplace 'fsrupsc', 'fsrupsc'
  $t = $t -creplace 'FsrUpsc', 'FsrUpsc'
  # class renames
  $t = $t -creplace 'FsrSettings',    'FsrSettings'
  $t = $t -creplace 'FsrHooks',       'FsrHooks'
  $t = $t -creplace 'FsrTransformer', 'FsrTransformer'
  $t = $t -creplace 'FsrControl',     'FsrControl'
  $t = $t -creplace '\bAgentLog\b',    'FsrLog'
  $t = $t -creplace '\bConfig\b',      'FsrConfig'
  # package prefix / file labels
  $t = $t -creplace 'fsrupsc\.(/|%2F)?', 'fsrupsc'
  $t = $t -creplace 'fsrupsc\.', 'fsrupsc.'
  $t = $t -creplace 'fsrupsc/',  'fsrupsc/'
  $t = $t -creplace 'fsr_',  'fsr_'
  $t = $t -creplace 'fsrupsc',   'fsrupsc'
  # package lines / same-package imports
  $t = $t -creplace '(?m)^package (fsrupsc|agent);',   'package fsrupsc;'
  $t = $t -creplace '(?m)^import (fsrupsc|agent|fsrupsc)\.[A-Za-z0-9_$]+;\r?\n', ''
  if ($t -ne $raw) {
    [IO.File]::WriteAllText($f.FullName, $t, (New-Object Text.UTF8Encoding $false))
    Write-Output ("rebranded " + $f.FullName.Substring($Root.Length + 1))
  }
}
Write-Output 'done'
