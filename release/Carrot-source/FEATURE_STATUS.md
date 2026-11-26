# Carrot feature status

## Working

- Always-on native Windows tray process with Carrot/Jarvis wake aliases and double-clap wake.
- Continuous follow-up window, local Faster Whisper wake/command transcription, Windows wake fallback, spoken responses, real microphone waveform, and visible assistant states.
- Local Ollama conversation with recent context, approved memories, warm-model startup, and deterministic tool routing.
- One-time/daily reminders, desktop notifications, list/delete/snooze, atomic persistence, and duplicate prevention.
- Persistent dismiss-only reminder popup; fired one-time reminders remain stored and visible until deleted.
- Persistent tasks: add, list, complete, and delete.
- Persistent user-approved memory: remember, list, and forget.
- Allowlisted app launching: PowerPoint, Word, Excel, Notepad, Calculator, File Explorer, and Chrome.
- Real CPU, RAM, disk, uptime, process-count, and network telemetry.
- Responsive local HUD with transcript, logs, tasks, memories, reminders, system telemetry, provider health, and wake controls.
- Dashboard-close dormant mode unloads Ollama while preserving wake detection and reminder scheduling.
- Localhost-only service, session-cookie authentication, rate limiting, CSP/frame protection, argument validation, atomic data writes, audit log, health/readiness endpoints, and emergency voice shutdown.
- Windows startup shortcut, packaged runtime, automated Java tests, JavaScript syntax check, and restart-persistence tests.

## Experimental

- Double-clap detection: microphone hardware and room acoustics affect reliability.
- Wake recognition and commands use local Faster Whisper. Microphone gain, heavy room noise, and very short/quiet wake phrases can still affect detection.
- Local AI latency: approximately one second when warm on this PC; first model load is slower and depends on available RAM/GPU.

## Requires configuration or an external connector

- Calendar, email, messaging, smart-home/IoT, phone, cloud LLM, and private-account integrations.
- GPU temperature and vendor-specific hardware telemetry.
- Live web search and webpage summarization with citations.
- Camera/vision models and browser automation.

## Unsupported for safety in this build

- Arbitrary model-generated shell commands.
- Silent purchases, form submission, publishing, financial activity, account changes, or software installation.
- Unrestricted filesystem, keyboard, or mouse control.
- Background camera recording or permanent microphone recording.
