package com.prash.kosh.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets that live on this phone only: the database key and the saved backup password.
 * Each is wrapped (AES-256-GCM) with a key held by the Android Keystore, so the plain value
 * never touches disk.
 */
object KeyManager {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "kosh_master_v1"
    private const val PREFS = "kosh_keys"

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun wrap(plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, masterKey())
        val out = c.iv + c.doFinal(plain)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun unwrap(stored: String): ByteArray {
        val all = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, all, 0, 12))
        return c.doFinal(all, 12, all.size - 12)
    }

    @Synchronized
    fun databasePassphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString("db_key", null)?.let { return unwrap(it) }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString("db_key", wrap(key)).commit()
        return key
    }

    fun saveBackupPassword(context: Context, password: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("backup_pw", wrap(password.toByteArray(Charsets.UTF_8))).apply()
    }

    fun backupPassword(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("backup_pw", null)
            ?.let { String(unwrap(it), Charsets.UTF_8) }
}
