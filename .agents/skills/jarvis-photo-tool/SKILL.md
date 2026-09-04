---
name: jarvis-photo-tool
description: Add or modify an on-device photo editing tool for Jarvis (auto-enhance, background removal, object erase, sky replace, bokeh, upscale, filters, smart crop, retouch, batch ops). Use when implementing the PhotoToolEngine contract in :core:ml, the preview/full-res pipeline, model loading, or photo tool UI in :feature:photo-tools.
---

# Jarvis Photo Tool

## Step 1: Implement the PhotoToolEngine Contract

All photo tools run fully on-device via `:core:ml` (ONNX Runtime / MediaPipe / TFLite) and implement (`.claude/skills/refences/07-PHOTO-TOOLS.md`, `.claude/skills/refences/10-API-REFERENCE.md` §4):

```kotlin
interface PhotoToolEngine {
    suspend fun preview(tool: PhotoToolId, input: Bitmap): Bitmap        // downsampled, fast
    suspend fun apply(tool: PhotoToolId, input: Uri, params: Map<String, Any>): Flow<PhotoOpProgress>
}

sealed class PhotoOpProgress {
    data class InProgress(val fraction: Float) : PhotoOpProgress()
    data class Complete(val resultUri: Uri) : PhotoOpProgress()
    data class Failed(val error: String, val fallbackAvailable: Boolean) : PhotoOpProgress()
}
```

- Run on `Dispatchers.Default`; any op that can exceed 1s shows progress UI (`.claude/skills/refences/02-ARCHITECTURE.md` §4).
- Output resolution never exceeds input resolution — except the explicit Upscale tool.
- Every photo tool is also exposed as an agent-callable tool (`.claude/skills/refences/06-AGENT.md` §3) — follow `jarvis-agent-tool` for that registration; the engine stays the single implementation.

## Step 2: Preview → Confirm → Full-Res Pipeline (.claude/skills/refences/07-PHOTO-TOOLS.md §2)

```
Select photo(s) → Tool selection → Preview (downsampled, max 1024px long edge) → Confirm → Full-res processing → Save/Share
```

- Preview first, always — this is what makes the tool feel instant when full-res takes seconds.
- Sub-300ms tools may skip the preview/confirm split (preview *is* the full-res result at that size).
- Full-res: tiled processing for images that would exceed available memory.
- **Non-destructive by default:** edits write a new MediaStore entry (or app vault); "overwrite original" is an explicit per-save opt-in.

## Step 3: Model Loading Strategy (.claude/skills/refences/07-PHOTO-TOOLS.md §4)

- Lazy-load on first use; keep warm for a session-scoped window (evicted after 5 min idle, or immediately on `onTrimMemory` under memory pressure).
- Small models bundle in the APK within the 800MB budget; larger ones (upscaler) download on first use from the checksummed manifest (same pattern as local LLM models, `.claude/skills/refences/05-LLM-PROVIDERS.md` §7).
- Models: encrypted at rest, checksum-verified before load.

## Step 4: Deterministic Fallback (.claude/skills/refences/07-PHOTO-TOOLS.md §5)

Every ML-based tool has a non-ML fallback path (basic crop/contrast adjustment) used when model loading fails — corrupt download, unsupported device, OOM. `Failed.fallbackAvailable` tells the UI to offer the degraded path instead of a dead button.

## Step 5: Batch Operations (.claude/skills/refences/07-PHOTO-TOOLS.md §3)

- Queue via WorkManager, process **sequentially** (not parallel) on `Dispatchers.Default` — no multiple large bitmaps in flight at once.
- Per-item progress in a batch sheet; cancel stops after the in-flight item completes (never a half-written file).

## Step 6: Privacy (non-negotiable, .claude/skills/refences/07-PHOTO-TOOLS.md §6, .claude/skills/refences/14-SECURITY.md)

- No photo pixel data ever leaves the device for these tools. Cloud image generation is a separate, explicitly-consented feature — out of scope here.
- No photo content, photo-derived embeddings, or EXIF data in any telemetry event.
- Face retouch does per-image, per-session landmark detection only — no persistent face-recognition profile is built or stored.
- Scoped storage / photo picker only — no broad `MANAGE_EXTERNAL_STORAGE`.

## Step 7: Tests (.claude/skills/refences/13-TESTING.md)

Mandatory instrumented critical path: apply a filter → verify a **new file** is created and the original is untouched.
- Unit-test the fallback path (model load failure → deterministic degraded result, not an error).
- Batch: verify sequential processing and that cancel leaves no partial file.
- Memory-pressure: verify `onTrimMemory` eviction frees the model.
