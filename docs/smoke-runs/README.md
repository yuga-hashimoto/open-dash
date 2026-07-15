# Physical smoke-run artifacts

Each physical run is a dated file named `YYYY-MM-DD-<device>.md`. The file is
operator evidence, not generated test output. Keep the redacted
`VoiceMeasurementReport` and `VoiceAcceptanceReport` in the artifact, and do
not paste transcripts, credentials, or raw microphone audio.

## Acceptance session commands

Start or reset a run:

```bash
adb shell am startservice -n com.opendash.app/.service.VoiceService \
  -a com.opendash.app.START_ACCEPTANCE_RUN \
  --es acceptance_run_id "2026-07-15-pixel-tablet"
```

For each checklist step, record observation in documented order:

```bash
adb shell am startservice -n com.opendash.app/.service.VoiceService \
  -a com.opendash.app.OBSERVE_ACCEPTANCE_STEP \
  --es step_id wake_latency \
  --es note "10 attempts observed; latency from System Info"
```

After operator judgment, record the verdict explicitly (`true` or `false`):

```bash
adb shell am startservice -n com.opendash.app/.service.VoiceService \
  -a com.opendash.app.VERIFY_ACCEPTANCE_STEP \
  --es step_id wake_latency \
  --ez passed true \
  --es note "All ten detections met the room test criterion"
```

Export both reports:

```bash
adb shell am startservice -n com.opendash.app/.service.VoiceService \
  -a com.opendash.app.EXPORT_ACCEPTANCE_RUN
adb shell am startservice -n com.opendash.app/.service.VoiceService \
  -a com.opendash.app.EXPORT_MEASUREMENT
adb logcat -d | grep -A 200 -E 'VoiceAcceptanceReport|VoiceMeasurementReport'
```

An observed step is not a pass. A run is verified only after every required
step has an explicit operator verdict and the markdown artifact records the
device, Android version, room/setup, and pass criteria used.
