# Carrot Local Assistant

A local Windows system-tray assistant with voice activation, local Ollama conversation, reminders, tasks, user-approved memory, live telemetry, and allowlisted computer actions. User data stays in `%APPDATA%\JavaJarvisAssist`.

## Try it

Carrot starts in the system tray, serves its command-center frontend locally at `http://127.0.0.1:17865`, and uses local Faster Whisper for both the wake phrase and accurate commands, with Windows Speech as a fast wake fallback. Say **“Carrot”**, wait for “Yes?”, and speak a command; or say the wake word and command together. **“Jarvis” remains available as a compatibility alias.** Double-clap wake is available as an optional mode in Settings but is disabled by default to avoid room-noise false wakes. You can also click the blue **C** tray icon and type in the frontend. The waveform moves from the real microphone signal and the HUD changes through `LISTENING`, `TRANSCRIBING`, `THINKING`, and `SPEAKING`.

- `remind me at 6:30 pm to call home`
- `remind me tomorrow at 9:00 am to send the report`
- `remind me on 2026-11-30 at 14:00 to join the meeting`
- `daily at 8:00 am remind me to take vitamins`
- `today`
- `list reminders`
- `delete reminder 2`
- `snooze 10 minutes` after a reminder notification
- `open PowerPoint`
- `open Word`, `open Excel`, `open Notepad`, `open Calculator`, `open File Explorer`, or `open Chrome`
- `what time is it`
- `what is today's date`
- `remember that I prefer concise answers`
- `what do you remember about me`
- `forget memory 1`
- `add task prepare the report`
- `list tasks`, `complete task 1`, or `delete task 1`
- Ask a normal question for a local Ollama answer
- `go to sleep`, `wake up Carrot`, or `shutdown Carrot`
- `help`
- `Carrot, today`

Closing the command window leaves Carrot running. Use the tray menu's **Exit** item to stop it.

## Build and startup

For a clean installation, run `powershell -ExecutionPolicy Bypass -File .\scripts\install-whisper.ps1` once. Then run `powershell -ExecutionPolicy Bypass -File .\build.ps1` and `powershell -ExecutionPolicy Bypass -File .\scripts\install-startup.ps1`. The packaged app includes its own Java runtime. The speech environment and model are cached under `%LOCALAPPDATA%\Carrot`; the startup shortcut is installed in your personal Windows Startup folder.

Ollama defaults to `llama3.2:3b`. Override it with `CARROT_OLLAMA_MODEL`; override the server with `CARROT_OLLAMA_URL`. The model is warmed asynchronously and kept loaded for 30 minutes. The deterministic command router always runs before the LLM, so reminders and computer actions do not depend on model output.

See [FEATURE_STATUS.md](FEATURE_STATUS.md), [ARCHITECTURE.md](ARCHITECTURE.md), and [SECURITY.md](SECURITY.md) for the honest implementation matrix and design details.
