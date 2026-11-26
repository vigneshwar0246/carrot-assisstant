$ErrorActionPreference = 'Stop'
$exe = (Resolve-Path "$PSScriptRoot\..\dist\Carrot\Carrot.exe").Path
$startup = [Environment]::GetFolderPath('Startup')
$oldShortcutPath = Join-Path $startup 'Java Jarvis Assist.lnk'
if (Test-Path -LiteralPath $oldShortcutPath) { Remove-Item -LiteralPath $oldShortcutPath }
$shortcutPath = Join-Path $startup 'Carrot.lnk'
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = $exe
$shortcut.WorkingDirectory = Split-Path $exe
$shortcut.Description = 'Start Carrot when I sign in'
$shortcut.Save()
Write-Host "Startup shortcut installed: $shortcutPath"
