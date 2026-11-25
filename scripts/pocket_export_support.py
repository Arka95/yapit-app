"""Makes Pocket's streaming state explicit for ONNX without changing model weights.

The state-export approach follows the MIT-licensed Pocket TTS implementation and
the public KevinAHM/lomotron exporter. These patches exist only in the export process.
"""

import torch
import torch.nn.functional as functional
from pocket_tts.modules.conv import StreamingConv1d, StreamingConvTranspose1d
from pocket_tts.modules import conv
from pocket_tts.modules.stateful_module import StatefulModule
from pocket_tts.modules.transformer import StreamingMultiheadAttention


def flatten_state(state):
    """Orders nested streaming tensors deterministically for ONNX inputs and outputs."""
    tensors = []
    for key in sorted(state):
        value = state[key]
        tensors.extend(flatten_state(value) if isinstance(value, dict) else [value])
    return tensors


def restore_state(template, tensors):
    """Reconstructs a fresh state dictionary while retaining the exported tensor order."""
    values = iter(tensors)

    def restore(node):
        return {key: restore(node[key]) if isinstance(node[key], dict) else next(values)
                for key in sorted(node)}

    return restore(template)


def attention_init(self, batch_size, sequence_length):
    """Allocates a fixed-capacity attention cache with an explicit position tensor."""
    return {
        "offset": torch.zeros(batch_size, dtype=torch.int64),
        "cache": torch.zeros(2, batch_size, sequence_length, self.num_kv_heads,
                             self.dim_per_head),
    }


def attention_cache(self, keys, values, state):
    """Writes new key/value rows out of place and exposes only the valid prefix."""
    position = state["offset"]
    cache = state["cache"].clone()
    cache[0, :, position:position + keys.shape[1]] = keys
    cache[1, :, position:position + values.shape[1]] = values
    state["cache"] = cache
    valid = cache[:, :, :position + keys.shape[1]]
    return valid[0], valid[1]


def attention_forward(self, query, model_state, attn_mask=None):
    """Runs the original attention projections with tensor-based positions and mask."""
    state = None if model_state is None else self.get_state(model_state)
    batch, length, _ = query.shape
    projected = self.in_proj(query)
    queries = projected[..., :self.attn_dim].view(
        batch, length, self.num_heads, self.dim_per_head)
    packed = projected[..., self.attn_dim:].view(
        batch, length, 2, self.num_kv_heads, self.dim_per_head)
    keys, values = torch.unbind(packed, dim=2)
    offset = torch.zeros((), dtype=torch.int64) if state is None else state["offset"][0]
    queries, keys = self.rope(queries, keys, offset=offset)
    if state is not None:
        keys, values = attention_cache(self, keys, values, state)
    if self.num_kv_heads != self.num_heads:
        keys = keys.repeat_interleave(self.num_heads // self.num_kv_heads, dim=2)
        values = values.repeat_interleave(self.num_heads // self.num_kv_heads, dim=2)
    rows = torch.arange(length).unsqueeze(1) + offset
    columns = torch.arange(keys.shape[1]).unsqueeze(0)
    delta = rows - columns
    allowed = delta >= 0
    if self.context is not None:
        allowed = allowed & (delta < self.context)
    mask = (torch.zeros_like(allowed, dtype=torch.float32)
            .masked_fill(~allowed, float("-inf"))) if attn_mask is None else attn_mask
    attended = functional.scaled_dot_product_attention(
        queries.transpose(1, 2), keys.transpose(1, 2), values.transpose(1, 2), mask)
    return self.out_proj(attended.transpose(1, 2).reshape(batch, length, self.attn_dim))


def conv_forward(self, value, model_state):
    """Carries convolution history explicitly, including the first replicated frame."""
    state = self.init_state(1, 0) if model_state is None else self.get_state(model_state)
    previous_length = state["previous"].shape[-1]
    if previous_length and self.pad_mode == "replicate":
        state["previous"] = torch.where(
            state["first"].view(-1, 1, 1), value[..., :1], state["previous"])
    if previous_length:
        value = torch.cat([state["previous"], value], dim=-1)
    result = self.conv(value)
    if previous_length:
        state["previous"] = value[..., -previous_length:]
        if self.pad_mode == "replicate":
            state["first"] = torch.zeros_like(state["first"])
    return result


def transpose_forward(self, value, model_state):
    """Returns decoded samples while preserving the overlap for the next chunk."""
    state = self.get_state(model_state)
    result = self.convtr(value)
    overlap = state["partial"].shape[-1]
    if overlap:
        result = torch.cat([result[..., :overlap] + state["partial"],
                            result[..., overlap:]], dim=-1)
        pending = result[..., -overlap:]
        if self.convtr.bias is not None:
            pending = pending - self.convtr.bias[:, None]
        state["partial"] = pending
        result = result[..., :-overlap]
    return result


def apply_export_patches():
    """Replaces resizing/in-place state updates only for this offline export process."""
    StatefulModule.increment_step = lambda self, state, increment=1: None
    StreamingMultiheadAttention.init_state = attention_init
    StreamingMultiheadAttention.forward = attention_forward
    StreamingMultiheadAttention.increment_step = (
        lambda self, state, increment=1: state.update(offset=state["offset"] + increment))
    StreamingConv1d.forward = conv_forward
    StreamingConvTranspose1d.forward = transpose_forward
    conv.get_extra_padding_for_conv1d = (
        lambda value, kernel_size, stride, padding_total=0:
        (stride - (value.shape[-1] - kernel_size + padding_total) % stride) % stride)
