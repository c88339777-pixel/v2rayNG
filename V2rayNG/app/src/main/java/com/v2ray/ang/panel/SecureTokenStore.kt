package com.v2ray.ang.panel

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SecureTokenStore {
    private const val PREFS = "panel_secure"
    private const val KEY_ALIAS = "vpn_hub_token_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    // Opening the Keystore is slow on many devices, so the key is resolved once per process.
    @Volatile
    private var cachedKey: SecretKey? = null

    private fun getKey(): SecretKey {
        cachedKey?.let { return it }

        return synchronized(this) {
            cachedKey ?: loadOrCreateKey().also { cachedKey = it }
        }
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or
                    KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE
                )
                .setKeySize(256)
                .build()
        )

        return generator.generateKey()
    }

    fun save(context: Context, token: String, slot: String = "") {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getKey())

        val encrypted = cipher.doFinal(
            token.toByteArray(StandardCharsets.UTF_8)
        )

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(
                "iv$slot",
                Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
            )
            .putString(
                "data$slot",
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
            )
            .apply()
    }

    fun get(context: Context, slot: String = ""): String? {
        return try {
            val prefs = context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )

            val iv = Base64.decode(
                prefs.getString("iv$slot", null) ?: return null,
                Base64.NO_WRAP
            )

            val data = Base64.decode(
                prefs.getString("data$slot", null) ?: return null,
                Base64.NO_WRAP
            )

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getKey(),
                GCMParameterSpec(128, iv)
            )

            String(
                cipher.doFinal(data),
                StandardCharsets.UTF_8
            )
        } catch (e: Exception) {
            cachedKey = null
            clear(context, slot)
            null
        }
    }

    fun clear(context: Context, slot: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove("iv$slot")
            .remove("data$slot")
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
