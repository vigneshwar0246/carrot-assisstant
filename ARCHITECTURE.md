# Architecture

```text
Windows Speech / Clap detector / Web command
                    │
                    ▼
        Deterministic command router
          │                    │
          │ registered action  │ ordinary question
          ▼                    ▼
 Reminder / Task / Memory   Local Ollama
 App launcher / Controls    llama3.2:3b
          │                    │
          └──────────┬─────────┘
                     ▼
          Audit + speech response
                     │
                     ▼
       Authenticated localhost HUD
```

- `JarvisApp` owns lifecycle, voice/clap listeners, registered command routing, the localhost API, system tray, and HUD state.
- `OllamaClient` is the replaceable local language-model provider. It has no authority to run tools.
- `MemoryStore`, `TaskStore`, and `ReminderStore` use local atomic files and expose typed operations.
- `SystemTelemetry` reads live operating-system values; it never creates synthetic telemetry.
- `AuditLog` records action category, time, success, and a sanitized detail string.
- The browser frontend is static HTML/CSS/JavaScript served only by the native application.

The deterministic router intentionally precedes Ollama. This keeps common actions fast and prevents natural-language model output from becoming executable code.
