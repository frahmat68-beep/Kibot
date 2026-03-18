package com.kibot.android.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureCredentialStore(
    context: Context,
) {
    private val preferences = context.getSharedPreferences("kibot_secure", Context.MODE_PRIVATE)

    fun saveCredentialBundle(cipherTextPlaintext: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(cipherTextPlaintext.toByteArray())
        preferences.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_BLOB, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun loadCredentialBundle(): String? {
        val iv = preferences.getString(KEY_IV, null) ?: return null
        val blob = preferences.getString(KEY_BLOB, null) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
        )
        return cipher.doFinal(Base64.decode(blob, Base64.NO_WRAP)).decodeToString()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(false)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "kibot_master_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_IV = "credential_iv"
        private const val KEY_BLOB = "credential_blob"
    }
}

