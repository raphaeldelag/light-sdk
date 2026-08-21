# Recorder (Light Phone III tool)

A reporter's voice-memo tool for the Light Phone III, built on the Light SDK.

- Record with the phone mic (`LightAudioRecorder`, AAC `.m4a`), review before saving, discard takes you don't want.
- **Marks**: tap the star during a take to drop a timestamped marker ("that's the quote"); tap a mark later to jump playback there.
- **Attribution**: after saving, pick what was agreed with the source (on record / background / off record); shown on the recording screen and tappable to change.
- **Transcripts**: the receiver transcribes each memo locally with Whisper (`--model small` by default; audio never leaves your Mac) and the recording screen's FETCH TRANSCRIPT line pulls the text back to the phone (kept locally once fetched).
- Each recording carries a JSON **sidecar** (`<name>.json`: recorded-at, duration, label, consent, markers) that renames, deletes, sends, and pulls together with the audio — so the terms and the moments survive the trip to your transcription pipeline.
- Give a memo a short label on the LP3 keyboard; the label is stored in the filename
  (`yyyyMMdd-HHmmss_label.m4a`), so files stay self-describing when pulled off the phone.
- List, play, scrub (15 s skips + touch seek), rename, delete.
- **Send to Mac** over local Wi-Fi: run `scripts/recorder_receiver.py` on the Mac, scan the QR it prints
  (or type the address) in the tool's Settings, then send from a recording's screen or "send all unsent".
  Android forbids plain HTTP, so the receiver serves HTTPS with a self-signed certificate whose SHA-256
  rides in the address as `#<hex>`; the tool pins it (trust-on-scan), so only that Mac is trusted.
- Recordings live in `files/shared/recordings/` (the SDK's `LightFileShare` directory), so LightOS's
  File Manager can expose them too once that ships.
- Permissions: `RECORD_AUDIO`, `INTERNET` (send to Mac), `CAMERA` (scan the receiver QR).

## Build / run

```bash
scripts/target.sh emu          # or: scripts/target.sh phone   (sets serverPackage in tool/lighttool.toml)
./gradlew :tool:installDebug
```

On a real LP3 the tool is dev-signed, so LightOS must be set to Developer options → External tools → All tools.

## Get recordings onto your Mac

Cable-free (works on a real phone, no adb needed):

```bash
pip3 install --user segno            # optional, for the QR code in the terminal
scripts/recorder_receiver.py          # prints https://<mac-ip>:8787/<token>#<cert-sha256> + QR
```

Then Recorder → Settings (gear) → SCAN QR FROM RECEIVER (or TYPE ADDRESS) → TEST CONNECTION.
Files land in `~/LightPhoneRecordings/` (change with `--dir`).

Emulator/adb fallback (debug builds only):

```bash
scripts/pull_recordings.sh              # adb run-as pull -> ~/LightPhoneRecordings/
```

Files are plain `.m4a`; drop them into any transcription pipeline.

## Layout

- `RecordingRepository.kt` — filename scheme, slugging, list/rename/delete (unit-tested, no Android deps)
- `DurationStore.kt` — DataStore cache of durations for the list view
- `HomeScreen.kt` — list + record button (`@InitialScreen`)
- `RecordScreen.kt` — permission → ready → recording → review → saved (+ label)
- `DetailScreen.kt` — playback, seek, rename, delete
- `LabelEditorScreen.kt` — LP3 keyboard text entry, returns the text as a screen result
- `Uploader.kt` — receiver address + certificate pin in DataStore, Ktor/OkHttp PUT, sent-file bookkeeping
- `SettingsScreen.kt` / `QrScanScreen.kt` — configure the receiver (QR or typed), test, send all unsent
