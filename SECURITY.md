# Security model

- HTTP binds to `127.0.0.1` only.
- Loading the dashboard establishes a random per-process HttpOnly, SameSite session cookie. API calls without it return `401`.
- Mutating APIs are limited to 120 requests per minute.
- Static responses use a restrictive Content Security Policy and deny framing.
- App launching uses a fixed allowlist and direct executable paths; no user text enters a shell.
- Reminder, task, and memory input is length-limited and encoded before storage.
- Static-file serving normalizes paths and rejects traversal outside the packaged `web` directory.
- Ollama answers cannot invoke actions. Actions must match registered command patterns.
- Shutdown and emergency-stop commands disable voice handling until a UI wake action.
- The app does not store passwords, access tokens, financial data, or microphone recordings.

The local Ollama service itself is managed separately. Keep it bound locally and do not expose port `11434` to untrusted networks.
