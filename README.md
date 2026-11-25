# YapIt

A Java Android app for creating named voice profiles, cloning a selected voice offline, queuing persistent background synthesis, receiving completion notifications, and browsing generated audio from app-private storage.

## What works

- Three tabs: **Profiles** for profile creation and recording, **Create** for profile/model selection and text input, and **Generated** for saved audio.
- Fixed black-and-white interface with black textbox outlines.
- Add named voice profiles and select the profile to use for generation. The last selection is restored on reopening.
- Records mono, 16-kHz, 16-bit WAV audio as `<profile-name>.sample` in each profile's private folder.
- Play or pause the selected profile's sample, or retake it. A retake replaces the saved sample only after a valid recording is completed.
- The recording button shows a pulsing icon and a tap-to-stop label while recording.
- Clones the selected voice in English with bundled October 2026 Pocket TTS, using full-precision ONNX models on the device CPU.
- Discovers providers/models from `app/src/main/assets/model_providers.json`.
- Persists the chosen provider, model ID, display-name snapshot, and version in every generation record.
- Runs synthesis as unique WorkManager work, including foreground progress for long jobs.
- Shows `QUEUED`, `LOADING MODEL`, `ENROLLING`, or `GENERATING` against the generation ID until completion.
- Posts completion/failure notifications and opens the Generated tab from the notification.
- Reloads previous records and audio from `files/generations/<generation-id>/` after process or device restart.
- Plays completed output with a Play/Pause toggle that resumes from the paused position.
- Completed items offer **Play**, **Open in folder**, **Share**, and **Delete**. Finished failures can also be deleted from history.
- Tap a generation's text preview to open its full, scrollable text. Select an excerpt or use **Copy text** to copy the original input.
- Generated WAV files are published to **Downloads/YapIt**; existing outputs are exported when Open in folder or Share is used.
- Shows the real background-worker state, live elapsed processing time, and time since the last progress report. Completed jobs retain their processing duration.

## Important model behavior

The default model is **Pocket TTS · Voice cloning**. Record 10–30 seconds of clear speech for a profile, select it, enter English text, and tap **Generate**. Inference is offline. The first generation copies the bundled model into app-private storage before loading it. The model bundle uses about 419 MiB, plus the bundled APK and runtime memory.

Pocket TTS model: `english_2026-10`, revision `db04f0e653473e5c7333c69686ac92b7460b0c1d`, exported from Pocket TTS 4.0.1 in float32; sherpa-onnx runtime: `2.0.3`; device: ARM64 CPU. Defaults are two inference threads, one sampling step, temperature 0.3, and a 12-second reference window. A disposable copy of the reference is faded into an 80 ms pause, preserving the original recording. Generated quiet intervals are retained at their original length.

Generation settings are owned by `model_providers.json`. Native resources are released after each generation. Voice profiles stay private; generated audio is also published to Downloads/YapIt for browsing and sharing. See the [Pocket model guide](docs/pocket/dev.readme.md) for export, compatibility and validation details.

The model selector also offers **default [agent voice]**, which uses Android system TTS without cloning the selected sample. The optional **Voice Clone API** adapter appears when `VOICE_API_BASE_URL` is configured.

Before the first build, run this command in an existing CPU Python environment. It installs the export dependencies, restores the checksum-pinned Android runtime, and exports and verifies the pinned model:

```powershell
.\scripts\setup-model.ps1 -Python python -InstallExportDependencies
```

The source model must be accessible through your local Hugging Face cache or account. Pass `-CacheDirectory` to reuse an existing hub cache. Exported weights stay under Git-ignored `data\models`; subsequent setup runs verify and reuse a complete bundle. Gradle bundles only the current model and its license in the APK.

Set the REST provider at build time without committing a key:

```bash
./gradlew :app:assembleDebug \
  -PVOICE_API_BASE_URL=https://your-host.example \
  -PVOICE_API_KEY=your-token
```

Install `app/build/outputs/apk/debug/app-debug.apk` on Android 12+ (minimum API 31).

The application ID and Java namespace are `com.example.yapit`, matching the supplied APK. Launcher artwork and the animated generation indicator are restored from `YapIt-arm64-v1.0-debug.apk`; the launcher uses an adaptive icon and the Android 12 system splash screen. The supplied APK is an older build and is unchanged; rebuild the source to get the Android 12 minimum and current target SDK.

You can also open the root directory in Android Studio. The project uses Java 17, compile/target SDK 35, AGP 8.7.3, and Gradle 8.9.

## REST adapter contract

### Enroll

`POST {baseUrl}/v1/profiles/enroll` as `multipart/form-data`:

- `profile_id`
- `display_name`
- `model_id`
- `sample` (`audio/wav`)

Expected `2xx` JSON:

```json
{"enrollment_id":"provider-specific-id"}
```

### Generate

`POST {baseUrl}/v1/generations` as JSON:

```json
{
  "generation_id": "uuid",
  "profile_id": "uuid",
  "enrollment_id": "provider-specific-id",
  "model_id": "default-clone",
  "text": "Editable input text"
}
```

Expected response: `2xx` with audio bytes (`audio/wav` or another playable audio MIME type). Add an API key to send `Authorization: Bearer <key>`.

## Add a model without changing UI or workflow

For another model offered by an existing provider, add an item under that provider's `config.models` array:

```json
{
  "id": "expressive-v2",
  "display_name": "Expressive Clone v2",
  "version": "2.1",
  "description": "New voice cloning model",
  "capabilities": ["TEXT_TO_SPEECH", "VOICE_CLONING", "VOICE_ENROLLMENT", "REQUIRES_NETWORK", "EMOTION_CONTROL"]
}
```

The selector, generation record, network constraint, enrollment cache, background job, loading states, history, and notification code require no modification.

For a new engine/provider:

1. Implement `ModelProvider` with a public no-argument constructor.
2. Return discoverable `ModelMetadata` and capabilities.
3. Perform any model-specific enrollment and synthesis only inside the adapter.
4. Register the class, provider ID, and config in `model_providers.json`.
5. Add a ProGuard/R8 keep rule if the implementation is in another package.

See `docs/ARCHITECTURE.md` for invariants and extension points.

## App-private storage

```text
files/
├── profiles/
│   └── <profile-id>/
│       ├── profile.json
│       └── <profile-name>.sample
├── models/
│       └── pocket-tts-english-2026-10-fp32/
└── generations/
    └── <generation-id>/
        ├── metadata.json
        └── audio.wav
```

Existing single-profile recordings migrate automatically without deleting the original recording. Changing a profile's sample invalidates only that profile's cached per-model enrollments. Queued work uses its stored profile ID even if another profile is later selected.

The form resizes above the keyboard and scrolls vertically. The multiline text field also supports vertical scrolling.

Minimizing the app keeps active generation running through WorkManager and its foreground notification. Timing excludes time spent queued for Android scheduling. Delete asks for confirmation, then removes the selected generation's history, private audio and Downloads copy while retaining its voice profile.

## Model attribution and testing

Pocket TTS is by Kyutai. YapIt's export tooling adapts the public streaming-state export approach for the current model and sherpa-onnx graph interface. Model weights are licensed under CC BY 4.0; the bundle retains the license, attribution, checkpoint revision and artifact checksums. The sherpa-onnx runtime is Apache-2.0 licensed; its license is bundled under `licenses`. Export-tool attribution is retained in `scripts\licenses`.

Run `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` to test profile isolation, legacy migration, enrollment isolation, reference-window trimming and text preparation, check Android compatibility, and build the APK. These unit tests use temporary files and synthetic arrays without loading a model or using a microphone.

## Security notes

- Do not commit API keys to `gradle.properties`; pass them through user-level Gradle properties or CI secrets.
- Voice samples remain in app-private internal storage; generated audio also appears in Downloads/YapIt.
- Before production, add certificate pinning where appropriate, retention/deletion controls, and explicit consent/privacy copy for biometric voice data.
