$ErrorActionPreference = 'Stop'
$releaseRoot = Join-Path $PSScriptRoot '..\release'
$stage = Join-Path $releaseRoot 'Carrot-source'
$zip = Join-Path $releaseRoot 'Carrot-source.zip'
if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stage | Out-Null
Copy-Item -Recurse (Join-Path $PSScriptRoot '..\src') $stage
Copy-Item -Recurse (Join-Path $PSScriptRoot '..\test') $stage
Copy-Item -Recurse (Join-Path $PSScriptRoot '..\web') $stage
Copy-Item -Recurse $PSScriptRoot $stage
Copy-Item (Join-Path $PSScriptRoot '..\build.ps1') $stage
Copy-Item (Join-Path $PSScriptRoot '..\README.md') $stage
Copy-Item (Join-Path $PSScriptRoot '..\FEATURE_STATUS.md') $stage
Copy-Item (Join-Path $PSScriptRoot '..\ARCHITECTURE.md') $stage
Copy-Item (Join-Path $PSScriptRoot '..\SECURITY.md') $stage
Copy-Item (Join-Path $PSScriptRoot '..\.env.example') $stage
if (Test-Path -LiteralPath $zip) { Remove-Item -LiteralPath $zip }
Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $zip -CompressionLevel Optimal
Write-Host "Sanitized source package: $zip"
