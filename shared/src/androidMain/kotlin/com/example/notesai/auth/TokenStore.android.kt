package com.example.notesai.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Android token storage: the payload is encrypted with an AES key that lives in the
 * Android Keystore (so the key itself never leaves secure hardware), and only the
 * ciphertext is written to SharedPreferences.
 */
class AndroidKeystoreTokenStore(context: Context) : TokenStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): AuthTokens? {
        val stored = prefs.getString(TOKENS_KEY, null) ?: return null
        return runCatching {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = blob.copyOfRange(GCM_IV_BYTES, blob.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            authJson.decodeFromString<AuthTokens>(cipher.doFinal(ciphertext).decodeToString())
        }.getOrNull()
    }

    override fun save(tokens: AuthTokens) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(authJson.encodeToString(tokens).encodeToByteArray())
        val blob = cipher.iv + ciphertext
        prefs.edit()
            .putString(TOKENS_KEY, Base64.encodeToString(blob, Base64.NO_WRAP))
            .apply()
    }

    override fun clear() {
        prefs.edit().remove(TOKENS_KEY).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "notesai.oauth.tokens"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val PREFS_NAME = "notesai.oauth"
        const val TOKENS_KEY = "tokens"
    }
}
