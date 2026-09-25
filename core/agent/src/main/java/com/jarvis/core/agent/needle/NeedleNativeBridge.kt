package com.jarvis.core.agent.needle

/**
 * Native JNI bridge for Needle 3 C/C++ engine.
 */
class NeedleNativeBridge {

    val isAvailable: Boolean by lazy {
        try {
            System.loadLibrary("needle_jni")
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun initNative(modelPath: String, depth: Int, confidenceFloor: Float): Long {
        return if (isAvailable) {
            try {
                nativeInit(modelPath, depth, confidenceFloor)
            } catch (_: Throwable) {
                0L
            }
        } else {
            0L
        }
    }

    fun routeNative(handle: Long, prompt: String, toolsCatalogJson: String): String? {
        return if (isAvailable && handle != 0L) {
            try {
                nativeRoute(handle, prompt, toolsCatalogJson)
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
    }

    fun destroyNative(handle: Long) {
        if (isAvailable && handle != 0L) {
            try {
                nativeDestroy(handle)
            } catch (_: Throwable) {
                // Ignore
            }
        }
    }

    private external fun nativeInit(modelPath: String, depth: Int, confidenceFloor: Float): Long
    private external fun nativeRoute(handle: Long, prompt: String, toolsCatalogJson: String): String?
    private external fun nativeDestroy(handle: Long)
}
