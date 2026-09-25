package com.jarvis.core.agent.needle

/**
 * Configuration for Needle 3 fast on-device capability router.
 *
 * @property depth Laddered depth (number of layers, 2 to 20). Default: 6 (optimized for mobile latency/RAM).
 * @property confidenceThreshold Calibrated confidence cutoff required for direct capability execution.
 * @property confidenceFloor The minimum confidence floor below which tool calls are suppressed (default 0.10f).
 * @property modelPath Path to local model weights (e.g., assets/needle3_depth6.cact).
 */
data class NeedleConfig(
    val depth: Int = 6,
    val confidenceThreshold: Float = 0.80f,
    val confidenceFloor: Float = 0.10f,
    val modelPath: String = "needle3_depth6.cact",
)
