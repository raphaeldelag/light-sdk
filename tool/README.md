# Recorder (Light Phone III tool)

A reporter's voice-memo tool for the Light Phone III, built on the Light SDK.

- Record with the phone mic (`LightAudioRecorder`, AAC `.m4a`), review before saving, discard takes you don't want.
- Give a memo a short label on the LP3 keyboard; the label is stored in the filename
  (`yyyyMMdd-HHmmss_label.m4a`), so files stay self-describing when pulled off the phone.
- List, play, scrub (15 s skips + touch seek), rename, delete.
- Only permission: `android.permission.RECORD_AUDIO`. No network.

## Build / run

```bash
scripts/target.sh emu          # or: scripts/target.sh phone   (sets serverPackage in tool/lighttool.toml)
./gradlew :tool:installDebug
```

On a real LP3 the tool is dev-signed, so LightOS must be set to Developer options → External tools → All tools.

## Get recordings onto your Mac

```bash
scripts/pull_recordings.sh              # -> ~/LightPhoneRecordings/, skips already-pulled files
scripts/pull_recordings.sh ~/Some/Dir --delete
```

Files are plain `.m4a`; drop them into any transcription pipeline.

## Layout

- `RecordingRepository.kt` — filename scheme, slugging, list/rename/delete (unit-tested, no Android deps)
- `DurationStore.kt` — DataStore cache of durations for the list view
- `HomeScreen.kt` — list + record button (`@InitialScreen`)
- `RecordScreen.kt` — permission → ready → recording → review → saved (+ label)
- `DetailScreen.kt` — playback, seek, rename, delete
- `LabelEditorScreen.kt` — LP3 keyboard text entry, returns the label as a screen result
