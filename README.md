# Carrot Local Assistant

A local Windows system-tray assistant for typed reminders and approved app launching. Reminder data stays on this PC in `%APPDATA%\JavaJarvisAssist\reminders.tsv`.

## Try it

Carrot starts in the system tray, serves its command-center frontend locally at `http://127.0.0.1:17865`, and listens through a tuned Windows Speech Recognition pipeline. Say **“Carrot”**, wait for “Yes?”, and speak a command; say the wake word and command together; or double clap and then speak. **“Jarvis” remains available as a compatibility alias.** You can also click the blue **C** tray icon and type in the frontend.

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
- `help`
- `Carrot, today`

Closing the command window leaves Carrot running. Use the tray menu's **Exit** item to stop it.

## Build and startup

Run `powershell -ExecutionPolicy Bypass -File .\build.ps1`, then `powershell -ExecutionPolicy Bypass -File .\scripts\install-startup.ps1`. The packaged app includes its own Java runtime. The startup shortcut is installed in your personal Windows Startup folder.
