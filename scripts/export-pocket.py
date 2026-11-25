"""Exports and verifies a floating-point Pocket bundle for sherpa's generic graph ABI."""

import argparse
import gc
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import shutil

import numpy as np
import onnx
import onnxruntime as ort
import torch
from pocket_tts.models.tts_model import TTSModel
from pocket_tts.modules.stateful_module import increment_steps, init_states
from pocket_tts.utils.utils import download_if_necessary

from pocket_export_support import apply_export_patches, flatten_state, restore_state

LANGUAGE = "english_2026-10"
REVISION = "db04f0e653473e5c7333c69686ac92b7460b0c1d"
GRAPH_NAMES = ["lm_main.onnx", "lm_flow.onnx", "encoder.onnx",
               "decoder.onnx", "text_conditioner.onnx"]


class FlowBackbone(torch.nn.Module):
    """Exposes transformer cache and protects the first six frames from premature EOS."""

    def __init__(self, model, template):
        super().__init__()
        self.model = model
        self.template = template

    def forward(self, sequence, embeddings, *tensors):
        state = restore_state(self.template, tensors[:-1])
        sequence = torch.where(torch.isnan(sequence), self.model.bos_emb, sequence)
        projected = self.model.input_linear(sequence)
        hidden = self.model.backbone(projected, embeddings, sequence, state)
        padded = torch.cat([hidden, torch.zeros_like(embeddings[:, :1])], dim=1)
        conditioning = padded[:, 0]
        eos = self.model.out_eos(conditioning)
        eos = torch.where(tensors[-1] < 6, torch.full_like(eos, -1000), eos)
        increment_steps(self.model, state, sequence.shape[1] + embeddings.shape[1])
        return conditioning, eos, *flatten_state(state), tensors[-1] + sequence.shape[1]


class FlowStep(torch.nn.Module):
    """Exports the original floating-point flow network without embedding a sampler."""

    def __init__(self, model):
        super().__init__()
        self.model = model.flow_net

    def forward(self, conditioning, start, end, noise):
        return self.model(conditioning, start, end, noise)


class ReferenceEncoder(torch.nn.Module):
    """Preserves the current checkpoint's audio projection and learned voice BOS."""

    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, audio):
        prompt = self.model._encode_audio(audio)
        return torch.cat([self.model.flow_lm.bos_before_voice, prompt], dim=1)


class AudioDecoder(torch.nn.Module):
    """Decodes quantizer-space latents once, carrying explicit streaming state."""

    def __init__(self, model, template):
        super().__init__()
        self.model = model
        self.template = template
        # sherpa initializes state inputs to zero; invert first-frame flags at the ABI.
        self.inverted_flags = [t.dtype == torch.bool and bool(torch.all(t))
                               for t in flatten_state(template)]

    def forward(self, latent, *tensors):
        decoded = [~value if inverted else value
                   for value, inverted in zip(tensors, self.inverted_flags)]
        state = restore_state(self.template, decoded)
        raw = latent * self.model.flow_lm.emb_std + self.model.flow_lm.emb_mean
        audio = self.model.mimi.decode_from_latent(raw, state)
        increment_steps(self.model.mimi, state, latent.shape[1] * 16)
        encoded = [~value if inverted else value
                   for value, inverted in zip(flatten_state(state), self.inverted_flags)]
        return audio, *encoded


class TextConditioner(torch.nn.Module):
    """Maps the current vocabulary's integer token IDs to checkpoint embeddings."""

    def __init__(self, model):
        super().__init__()
        self.model = model.flow_lm.conditioner

    def forward(self, tokens):
        return self.model(tokens)


def export_graph(module, arguments, path, inputs, outputs, dynamic):
    """Writes one self-contained graph with explicit dynamic sequence dimensions."""
    with torch.no_grad():
        torch.onnx.export(
            module.eval(), arguments, str(path), input_names=inputs, output_names=outputs,
            opset_version=17, dynamo=False, external_data=False, dynamic_axes=dynamic,
        )
    graph = onnx.load(path)
    if graph.ir_version > 10:
        graph.ir_version = 10
        onnx.save(graph, path)
    onnx.checker.check_model(str(path))
    print(f"Exported {path.name}", flush=True)


def session(path):
    """Creates a bounded CPU verification session with no parallel thread fan-out."""
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    return ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])


def verify(module, arguments, graph, inputs):
    """Compares every graph output against the patched PyTorch computation."""
    with torch.no_grad():
        expected = module(*arguments)
    expected = expected if isinstance(expected, tuple) else (expected,)
    runtime = session(graph)
    actual = runtime.run(None, {name: value.detach().numpy()
                               for name, value in zip(inputs, arguments)})
    differences = []
    for left, right in zip(expected, actual, strict=True):
        left = left.detach().numpy()
        if np.issubdtype(left.dtype, np.floating):
            np.testing.assert_allclose(left, right, rtol=2e-3, atol=2e-4, equal_nan=True)
            differences.append(float(np.max(np.abs(left - right))) if left.size else 0)
        else:
            np.testing.assert_array_equal(left, right)
    print(f"Verified {graph.name}; max absolute difference {max(differences, default=0):.6g}",
          flush=True)
    return max(differences, default=0)


def export_flow(model, output):
    """Exports the stateful language backbone and stateless flow step separately."""
    template = init_states(model.flow_lm, batch_size=1, sequence_length=2048)
    tensors = flatten_state(template) + [torch.zeros(1, dtype=torch.int64)]
    state_inputs = [f"state_{i}" for i in range(len(tensors))]
    state_outputs = [f"out_state_{i}" for i in range(len(tensors))]
    main = FlowBackbone(model.flow_lm, template)
    arguments = (torch.randn(1, 1, 32), torch.randn(1, 3, 1024), *tensors)
    inputs = ["sequence", "text_embeddings", *state_inputs]
    export_graph(main, arguments, output / "lm_main.onnx", inputs,
                 ["conditioning", "eos_logit", *state_outputs],
                 {"sequence": {1: "sequence_length"},
                  "text_embeddings": {1: "text_length"}})
    checks = {"lm_main": verify(main, arguments, output / "lm_main.onnx", inputs)}
    for sequence_length, text_length in [(0, 7), (1, 0)]:
        values = (torch.randn(1, sequence_length, 32),
                  torch.randn(1, text_length, 1024), *tensors)
        verify(main, values, output / "lm_main.onnx", inputs)
    flow = FlowStep(model.flow_lm)
    arguments = (torch.randn(1, 1024), torch.zeros(1, 1),
                 torch.ones(1, 1), torch.randn(1, 32))
    export_graph(flow, arguments, output / "lm_flow.onnx", ["c", "s", "t", "x"],
                 ["flow"], {})
    checks["lm_flow"] = verify(flow, arguments, output / "lm_flow.onnx",
                               ["c", "s", "t", "x"])
    return checks


def export_codec(model, output):
    """Exports current reference conditioning, token lookup and streaming audio decoder."""
    encoder = ReferenceEncoder(model)
    arguments = (torch.randn(1, 1, 24000),)
    export_graph(encoder, arguments, output / "encoder.onnx", ["audio"], ["embedding"],
                 {"audio": {2: "audio_samples"}})
    checks = {"encoder": verify(encoder, arguments, output / "encoder.onnx", ["audio"])}
    verify(encoder, (torch.randn(1, 1, 32640),), output / "encoder.onnx", ["audio"])
    conditioner = TextConditioner(model)
    arguments = (torch.randint(0, 4000, (1, 20)),)
    export_graph(conditioner, arguments, output / "text_conditioner.onnx",
                 ["tokens"], ["embedding"], {"tokens": {1: "text_length"}})
    checks["text_conditioner"] = verify(
        conditioner, arguments, output / "text_conditioner.onnx", ["tokens"])
    template = init_states(model.mimi, batch_size=1, sequence_length=8192)
    tensors = [torch.zeros_like(value) for value in flatten_state(template)]
    decoder = AudioDecoder(model, template)
    inputs = ["latent", *[f"state_{i}" for i in range(len(tensors))]]
    outputs = ["audio", *[f"out_state_{i}" for i in range(len(tensors))]]
    arguments = (torch.randn(1, 1, 32), *tensors)
    export_graph(decoder, arguments, output / "decoder.onnx", inputs, outputs,
                 {"latent": {1: "latent_frames"}})
    checks["decoder"] = verify(decoder, arguments, output / "decoder.onnx", inputs)
    verify(decoder, (torch.randn(1, 4, 32), *tensors), output / "decoder.onnx", inputs)
    return checks


def write_bundle(model, output, checks, license_path):
    """Pins model/tokenizer provenance and file checksums for repeatable Android builds."""
    source = Path(download_if_necessary(model.config.flow_lm.lookup_table.tokenizer_path))
    tokenizer = json.loads(source.read_text(encoding="utf-8"))
    if tokenizer["model"]["type"] != "Unigram":
        raise ValueError("The Android tokenizer requires a Unigram vocabulary")
    vocabulary = tokenizer["model"]["vocab"]
    for name, value in {
        "vocab.json": {piece: index for index, (piece, _) in enumerate(vocabulary)},
        "token_scores.json": {piece: score for piece, score in vocabulary},
    }.items():
        (output / name).write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
    shutil.copyfile(source, output / "tokenizer.json")
    shutil.copyfile(license_path, output / "LICENSE")
    (output / "README.md").write_text(
        "# Pocket TTS English October 2026\n\n"
        "Kyutai english_2026-10; floating-point ONNX export by YapIt.\n"
        f"Checkpoint revision: {REVISION}\n\n"
        "Voice BOS and the current speaker projection are included in encoder.onnx. "
        "The decoder accepts quantizer-space latents and applies the quantizer once. "
        "Graphs use sherpa-onnx's ordered streaming-state interface. "
        "See the project model guide for generation settings and reproducibility.\n",
        encoding="utf-8",
    )
    files = GRAPH_NAMES + ["vocab.json", "token_scores.json", "tokenizer.json", "LICENSE", "README.md"]
    manifest = {
        "schema_version": 1, "model": LANGUAGE, "revision": REVISION, "precision": "float32",
        "pocket_tts_version": importlib.metadata.version("pocket-tts"),
        "sample_rate": 24000, "voice_bos_in_encoder": True,
        "minimum_frames_before_eos": 6, "verification_max_absolute_error": checks,
        "files": {name: {"sha256": hashlib.sha256((output / name).read_bytes()).hexdigest(),
                         "bytes": (output / name).stat().st_size} for name in files},
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")


def main():
    """Exports the pinned current English checkpoint; downloads occur only when uncached."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="Destination model bundle")
    parser.add_argument("--cache-directory", type=Path, help="Existing Hugging Face hub cache")
    parser.add_argument("--license", type=Path, required=True, help="Upstream model CC BY 4.0 license")
    parser.add_argument("--component", choices=["all", "flow", "codec"], default="all")
    args = parser.parse_args()
    if args.cache_directory:
        os.environ["HF_HUB_CACHE"] = str(args.cache_directory.resolve())
        from huggingface_hub import constants
        constants.HF_HUB_CACHE = str(args.cache_directory.resolve())
    torch.set_num_threads(1)
    torch.manual_seed(42)
    model = TTSModel.load_model(language=LANGUAGE).cpu().eval()
    if REVISION not in str(model.config.weights_path) or not model.has_voice_cloning:
        raise ValueError("Unexpected checkpoint revision or missing voice-cloning support")
    apply_export_patches()
    args.output.mkdir(parents=True, exist_ok=True)
    checks = {}
    with torch.no_grad():
        if args.component in {"all", "flow"}:
            checks.update(export_flow(model, args.output))
        if args.component in {"all", "codec"}:
            checks.update(export_codec(model, args.output))
    if all((args.output / name).is_file() for name in GRAPH_NAMES):
        write_bundle(model, args.output, checks, args.license)
    del model
    gc.collect()


if __name__ == "__main__":
    main()
