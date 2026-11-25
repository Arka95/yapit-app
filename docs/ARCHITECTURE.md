# Extensible model architecture

## Stable orchestration layer

`GenerationWorker` owns orchestration only:

1. Load the immutable generation record.
2. Resolve its persisted provider/model IDs through `ModelRegistry`.
3. Load the voice profile by the immutable profile ID saved in the generation record.
4. Lazily enroll when `VOICE_ENROLLMENT` is advertised and no enrollment exists for the model key.
5. Ask the adapter to write audio to a requested temporary destination.
6. Atomically publish the output, update metadata, and notify.

Providers never own WorkManager IDs, filesystem schemas, RecyclerView state, tabs, or notification channels. Adding a provider therefore cannot require changes to those concerns.

## Profiles and local inference

`VoiceProfileStore` owns named profiles under `files/profiles/<profile-id>`, with independent sample files and enrollment caches. The single-profile layout migrates on first access, preserving the original profile ID, sample bytes and enrollments. `UserPreferences` persists the selected profile ID. Recording temporarily disables profile switching so a completed sample cannot be assigned to another profile.

`PocketTtsProvider` is the default cloning adapter. It copies the versioned October 2026 float32 model into app-private `files/models` only when a generation starts and opens the sherpa-onnx CPU runtime. The exported encoder includes the current speaker projection and learned voice BOS; graph state handles current decoder initialization and premature-EOS protection.

`PocketAudioPreparation` caps a disposable reference window, trims trailing silence, fades its final 20 ms, and appends an 80 ms pause. The profile's original WAV bytes remain unchanged. The `.sample` suffix is the application's naming convention; the contents remain standard WAV. The native engine resamples prepared reference audio to 24 kHz. Explicit temperature 0.3, one flow step and silence scale 1 preserve the quality baseline. Each worker owns and releases its model session; model weights remain reusable on disk. There is no automatic fallback to system TTS. See the [Pocket model guide](pocket/dev.readme.md) for the graph interface and export checks.

The UI uses a fixed monochrome light theme. The activity applies system-bar and keyboard insets to the root layout; the weighted scroll view keeps form controls reachable above the keyboard.

`GeneratedAudioFiles` publishes WAV output through MediaStore into Downloads/YapIt. The UI exports older outputs lazily when browsing or sharing. Share uses a read-granted content URI; folder browsing uses the system file manager. Deletion removes the selected generation's public copy, private files and metadata after confirmation, without touching profile data.

Generation metadata keeps processing start/end timestamps and the last progress message. The Generated page combines these with live WorkManager state so queued/retry work is distinguished from a running worker; a lifecycle-bound UI ticker updates elapsed time without rerunning inference.

## Provider boundary

`ModelProvider` is a blocking worker-side SPI:

- `isAvailable(...)` controls registration availability.
- `discoverModels(...)` supplies metadata and capabilities.
- `openSession(...)` performs provider-specific model/client loading and returns a scoped `ModelSession`.
- `ModelSession.enroll(...)` turns the common WAV sample into a provider enrollment ID.
- `ModelSession.generate(...)` writes output to the destination chosen by the orchestration layer.
- `ModelSession.close()` releases ONNX/native/TTS resources deterministically.

A provider may wrap JNI/ONNX, a bound Android service, an SDK, or HTTP. Keep provider-specific model loading, resource lifetime, and inference behind these interfaces.

## Capability-driven behavior

Capabilities are data, not UI branches:

- `VOICE_ENROLLMENT` requires a sample and enables lazy enrollment.
- `REQUIRES_NETWORK` causes the scheduler to add a connected-network constraint.
- `OFFLINE`, `VOICE_CLONING`, `EMOTION_CONTROL`, and `STREAMING` are discoverable metadata available for future controls.

Unknown capability strings are ignored for forward compatibility. New enum values can be introduced without changing persistence because capabilities are not needed to reopen an existing audio sample.

## Configuration and discovery

`model_providers.json` registers provider class names and provider configuration. `ModelRegistry` instantiates providers through reflection and asks each one to discover models. A future provider can replace static model lists with device/SDK/server discovery while returning the same `ModelMetadata` type.

## Persistence invariants

Every generation snapshots:

- generation and profile IDs;
- full input text;
- provider ID;
- model ID;
- model display name;
- model version;
- status/timestamps;
- output name/MIME type or error.

Provider and model IDs are the stable lookup key; name/version snapshots keep history understandable after registration metadata changes. WorkManager persists execution, while `metadata.json` is the UI/source-of-truth history. These layers intentionally remain independent.
