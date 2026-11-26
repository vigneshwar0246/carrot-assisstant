$ErrorActionPreference = 'Stop'

$jdk = Get-ChildItem -Directory "$env:ProgramFiles\Eclipse Adoptium\jdk-*" -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -First 1
if (-not $jdk) { throw 'JDK not found. Install Eclipse Temurin JDK 21 first.' }

$javac = Join-Path $jdk.FullName 'bin\javac.exe'
$jar = Join-Path $jdk.FullName 'bin\jar.exe'
$jpackage = Join-Path $jdk.FullName 'bin\jpackage.exe'
$out = Join-Path $PSScriptRoot 'build\classes'
$testOut = Join-Path $PSScriptRoot 'build\test-classes'
$jarFile = Join-Path $PSScriptRoot 'build\jarvis.jar'
$packageInput = Join-Path $PSScriptRoot 'build\package-input'
$dist = Join-Path $PSScriptRoot 'dist'

New-Item -ItemType Directory -Force $out, $testOut | Out-Null
& $javac -encoding UTF-8 -d $out (Get-ChildItem "$PSScriptRoot\src" -Recurse -Filter *.java).FullName
if ($LASTEXITCODE) { throw 'Application compilation failed.' }
& $javac -encoding UTF-8 -cp $out -d $testOut (Get-ChildItem "$PSScriptRoot\test" -Recurse -Filter *.java).FullName
if ($LASTEXITCODE) { throw 'Test compilation failed.' }
& (Join-Path $jdk.FullName 'bin\java.exe') -ea -cp "$out;$testOut" com.jarvis.assist.JarvisAppTest
if ($LASTEXITCODE) { throw 'Tests failed.' }

& $jar --create --file $jarFile --main-class com.jarvis.assist.JarvisApp -C $out .
if ($LASTEXITCODE) { throw 'JAR creation failed.' }
New-Item -ItemType Directory -Force $packageInput | Out-Null
Copy-Item -Force $jarFile (Join-Path $packageInput 'jarvis.jar')
Copy-Item -Force "$PSScriptRoot\scripts\voice-listener.ps1" (Join-Path $packageInput 'voice-listener.ps1')
$webInput = Join-Path $packageInput 'web'
if (Test-Path $webInput) { Remove-Item -Recurse -Force $webInput }
Copy-Item -Recurse "$PSScriptRoot\web" $webInput
if (Test-Path "$dist\Carrot") { Remove-Item -Recurse -Force "$dist\Carrot" }
if (Test-Path "$dist\Java Jarvis Assist") { Remove-Item -Recurse -Force "$dist\Java Jarvis Assist" }
New-Item -ItemType Directory -Force $dist | Out-Null
& $jpackage --type app-image --input $packageInput --main-jar (Split-Path $jarFile -Leaf) `
    --main-class com.jarvis.assist.JarvisApp --name 'Carrot' --dest $dist `
    --description 'Local reminders and approved app launcher' --vendor 'Local'
if ($LASTEXITCODE) { throw 'Windows app packaging failed.' }
Write-Host "Built: $dist\Carrot\Carrot.exe"
