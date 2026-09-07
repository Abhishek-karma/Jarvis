package com.jarvis.core.database.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton


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


    fun removeKeysNotIn(providerIds: Set<String>): Int {
        val orphans = prefs.all.keys.filter { it.startsWith("key_") && it.removePrefix("key_") !in providerIds }
        if (orphans.isEmpty()) return 0
        prefs.edit().apply { orphans.forEach { remove(it) } }.apply()
        return orphans.size
    }
}
