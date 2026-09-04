# Photo Tools

All tools in this doc run fully on-device (per `03-FEATURES.md` Feature 5) via `:core:ml`, using ONNX Runtime / MediaPipe / TFLite depending on the model. No photo pixel data ever leaves the device for these tools; cloud image generation (a separate, explicitly-consented feature) is out of scope here.

## 1. Tool Catalog

| Tool | Model class | Typical latency (mid-tier device, 12MP image) |
|---|---|---|
| Auto-enhance (exposure/color/contrast) | lightweight CNN, on-device | < 300ms |
| Background removal / subject cutout | segmentation model (MediaPipe Selfie/Image Segmenter) | < 500ms |
| Object erase (magic eraser) | inpainting model, tiled | 1–3s depending on erase area |
| Sky replacement | segmentation + blending | < 1s |
| Portrait blur (bokeh simulation) | depth estimation + Gaussian | < 500ms |
| Upscale / super-resolution | ESRGAN-class, quantized | 2–5s for 2x on 12MP |
| Style filters (preset LUTs) | non-ML, GPU shader-based | < 100ms |
| Smart crop / auto-straighten | saliency detection | < 200ms |
| Face retouch (skin smoothing, blemish) | landmark detection + local smoothing | < 400ms |
| Text/watermark removal | inpainting, same backbone as object erase | 1–3s |
| Batch operations (apply one tool to N photos) | any of the above, queued | linear in N, see §3 |

This is the same catalog exposed as agent-callable tools per `06-AGENT.md §3`.

## 2. Pipeline

```
Select photo(s) → Tool selection → Preview (downsampled, fast) → Confirm → Full-res processing → Save/Share
```

- **Preview stage:** every tool first runs on a downsampled copy (max 1024px long edge) so the user sees a near-instant result before committing to full-resolution processing — this is what keeps the "< 300ms" latencies above feeling instant even when full-res processing takes longer.
- **Full-res stage:** triggered only on explicit confirm (or immediately for sub-300ms tools where the preview *is* the full-res result at that size). Tiled processing for images that would otherwise exceed available memory (`02-ARCHITECTURE.md §4` concurrency notes, `03-FEATURES.md` Feature 5 memory-pressure handling).
- **Non-destructive by default:** edits produce a new file (gallery or app vault); "overwrite original" is an explicit opt-in per save, not the default.

## 3. Batch Operations

- Queued via `WorkManager`, processed sequentially (not parallel) on `Dispatchers.Default` to avoid memory pressure from multiple large bitmaps in flight simultaneously.
- Progress shown per-item in a batch progress sheet; cancel stops after the in-flight item completes (avoids leaving a half-written file).

## 4. Model Loading Strategy

- Each tool's model is lazy-loaded on first use (`03-FEATURES.md` Feature 5) and kept warm in memory for a session-scoped period (evicted after 5 minutes of no photo-tool activity, or immediately under system memory pressure via `onTrimMemory`).
- Models are bundled with the app where small enough to fit the 800MB budget (`01-PRD.md §9`); larger models (e.g., the upscaler) are downloaded on first use of that specific tool, from the same checksummed manifest pattern as local LLM models (`05-LLM-PROVIDERS.md §7`).

## 5. Quality & Fallback

- Every ML-based tool has a deterministic, non-ML fallback path (e.g., basic crop/contrast adjustment) used if model loading fails (corrupt download, unsupported device, out-of-memory) — the tool degrades gracefully rather than presenting a dead button.
- Output resolution never exceeds input resolution except for the explicit Upscale tool.

## 6. Privacy Notes

- No photo content, embeddings derived from photo content, or EXIF data is included in any telemetry event (`01-PRD.md §9`, `14-SECURITY.md §Telemetry`).
- Face-related tools (retouch) do not build or store a persistent face-recognition profile — landmark detection is per-image, per-session, discarded after processing.
