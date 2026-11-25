# Pocket TTS model integration

[Back to YapIt](../../README.md)

## Model and runtime

| Setting | Value |
|---|---|
| Model | Kyutai `english_2026-10`, voice-cloning checkpoint |
| Checkpoint revision | `db04f0e653473e5c7333c69686ac92b7460b0c1d` |
| Export SDK | Pocket TTS 4.0.1 |
| Precision | Float32 for language model, flow, encoder, decoder and token embeddings |
| Android runtime | sherpa-onnx 2.0.3, ARM64 CPU |
| Default generation | Two threads, one flow step, temperature 0.3 |
| Reference | First 12 seconds; configurable up to 30 seconds |
| Output | Mono 24 kHz WAV, original pause lengths |
| Storage | Git-ignored `data\models`; APK assets; lazy app-private copy |

`app\src\main\assets\model_providers.json` owns generation settings. Each generation records the effective model ID and revision label. The current model ID is `pocket-tts-en`; historical recordings retain their original metadata and remain playable.

## Preparing the bundle

Use an existing CPU environment with Pocket TTS available. The following command restores the Android runtime, installs the export requirements in the selected environment, downloads any uncached pinned weights, and numerically verifies the exported graphs:

```powershell
.\scripts\setup-model.ps1 -Python python -InstallExportDependencies -CacheDirectory C:\models\hub
```

Omit `-InstallExportDependencies` when the selected environment already satisfies `config\pocket-export-requirements.txt`. Existing valid bundles are checksum-verified and reused. Gated source weights require an already authorized Hugging Face account or a populated cache. No Python environment or model download is needed on the phone.

| Input | Processing | Output |
|---|---|---|
| Pinned checkpoint and tokenizer | Export the current speaker projection, voice BOS, flow network, token lookup and explicit streaming state. Compare graph outputs with PyTorch. | Five float32 ONNX graphs and tokenizer data |
| Exported graphs | Record revision, precision, SDK version, checksums and verification errors. | `manifest.json` and model attribution |
| Selected profile WAV | Cap a disposable window, trim quiet tail, fade 20 ms and append 80 ms silence; resample natively. | Reference conditioning without modifying profile storage |
| Text and conditioned model | Normalize whitespace/apostrophes, capitalize, append punctuation when needed; generate with explicit sampling settings. | WAV saved through the existing generation worker |

## Graph compatibility

The existing runtime discovers graph inputs and creates zero-initialized state tensors from their declared shapes. The exporter preserves that interface:

- The reference encoder includes the checkpoint's learned BOS-before-voice embedding.
- The backbone exposes key/value caches and a generation-frame counter; EOS is suppressed for the first six frames.
- First-frame decoder flags are inverted at the graph boundary so zero-initialized native inputs reproduce the checkpoint's true initial state.
- The decoder denormalizes latents and applies the quantizer exactly once.
- Fixed attention-cache capacities cover the native 500-frame generation limit; audio/text sequence dimensions remain dynamic.
- The current Unigram vocabulary and scores are exported from the checkpoint's pinned tokenizer JSON.

Native silence compression is disabled explicitly. Reference preparation does not apply loudness gain. The generated waveform receives a 5 ms onset fade.

## Validation

Fast application units exercise reference capping, trailing-silence handling, original-array preservation, silent-reference errors and text preparation. Existing profile, generation-deletion and JNI callback tests remain applicable.

Export validation compares graph outputs and streaming-state tensors with the corresponding PyTorch computation. It covers empty conditioning sequences, variable audio lengths and multiple decoder chunk sizes. This verifies graph numerics; final device checks must also compare speech and voice likeness.

Device comparisons use identical texts and reference windows, saved original WAVs, volume-matched listening copies and a local speaker-embedding evaluator. Cosine scores are relative comparisons, not identity probabilities or listening-quality scores. Process RSS includes runtime overhead, and phone/PC timings are not hardware-normalized.

## Attribution

Model weights are CC BY 4.0 by Kyutai. The export approach draws on the MIT-licensed Pocket TTS and KevinAHM/lomotron streaming-state exporter; its permission notice is retained under `scripts\licenses`. Model licenses and provenance travel with the APK bundle.
