import base64
import os
import sys
from pathlib import Path

from faster_whisper import WhisperModel


def emit(prefix: str, text: str = "") -> None:
    payload = base64.b64encode(text.encode("utf-8")).decode("ascii") if text else ""
    print(prefix + payload, flush=True)


try:
    model_name = os.environ.get("CARROT_WHISPER_MODEL", "base.en")
    cache = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "Carrot" / "whisper-models"
    model = WhisperModel(model_name, device="cpu", compute_type="int8", download_root=str(cache))
    print("WHISPER_READY", flush=True)
except Exception as exc:
    emit("WHISPER_ERROR:", f"Model startup failed: {exc}")
    raise

for raw in sys.stdin:
    raw = raw.strip()
    if not raw:
        continue
    try:
        audio_path = base64.b64decode(raw).decode("utf-8")
        segments, _ = model.transcribe(
            audio_path,
            language="en",
            beam_size=1,
            best_of=1,
            temperature=0,
            vad_filter=True,
            vad_parameters={"min_silence_duration_ms": 350},
            condition_on_previous_text=False,
            initial_prompt="Carrot Windows assistant command. Reminders, tasks, applications, time, date, and general questions.",
        )
        text = " ".join(segment.text.strip() for segment in segments).strip()
        emit("WHISPER:", text)
    except Exception as exc:
        emit("WHISPER_ERROR:", str(exc))
