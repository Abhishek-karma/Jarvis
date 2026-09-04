package com.jarvis.core.database.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * API key storage: AES-256-GCM, Android Keystore-wrapped master key.
 * Keys are never logged, never written to Room, never synced — only read by :core:network
 * when building the Authorization header for the key's own provider endpoint.
 */
@Singleton
class ApiKeyStore @Inject constructor(@ApplicationContext context: Context) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "jarvis_api_keys",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun getKey(providerId: String): String? = prefs.getString("key_$providerId", null)

    fun putKey(providerId: String, apiKey: String) {
        prefs.edit().putString("key_$providerId", apiKey).apply()
    }

    fun removeKey(providerId: String) {
        prefs.edit().remove("key_$providerId").apply()
    }
}
