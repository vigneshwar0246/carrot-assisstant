$ErrorActionPreference = 'Stop'
$environment = Join-Path $env:LOCALAPPDATA 'Carrot\whisper-env'
New-Item -ItemType Directory -Force -Path (Split-Path $environment) | Out-Null
if (-not (Test-Path (Join-Path $environment 'Scripts\python.exe'))) {
    uv venv $environment --python 3.11
    if ($LASTEXITCODE) { throw 'Could not create the Carrot speech environment.' }
}
$python = Join-Path $environment 'Scripts\python.exe'
uv pip install --python $python faster-whisper==1.2.1
if ($LASTEXITCODE) { throw 'Could not install the Carrot speech engine.' }
& $python -c "from faster_whisper import WhisperModel; WhisperModel('base.en', device='cpu', compute_type='int8', download_root=r'$env:LOCALAPPDATA\Carrot\whisper-models'); print('Whisper ready')"
if ($LASTEXITCODE) { throw 'Could not download the Carrot speech model.' }
