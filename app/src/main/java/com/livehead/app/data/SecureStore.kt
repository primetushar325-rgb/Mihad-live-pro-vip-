package com.livehead.app.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.livehead.app.core.AppLog
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the stream key encrypted with an AndroidKeyStore AES/GCM key.
 *
 *  - the key never exists in plain text on disk (IV + ciphertext only)
 *  - the key is never logged and never included in diagnostics
 *  - the file lives in noBackupFilesDir so it is excluded from cloud backups
 *
 * If the keystore is unavailable on some exotic device the store degrades to
 * "session-only": the key is kept in memory for the current process and wiped
 * on exit — save() simply reports false.
 */
object SecureStore {

    private const val TAG = "SecureStore"
    private const val KEY_ALIAS = "livehead_stream_key"
    private const val FILE = "streamkey.bin"
    private const val IV_LEN = 12

    @Volatile private var sessionKey: String? = null

    private fun masterKey(): SecretKey? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey) ?: run {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            gen.generateKey()
        }
    } catch (t: Throwable) {
        AppLog.w(TAG, "keystore unavailable: ${t.javaClass.simpleName}")
        null
    }

    private fun file(ctx: Context): File = File(ctx.noBackupFilesDir, FILE)

    /** Returns false when the key could not be persisted securely. */
    fun save(context: Context, value: String): Boolean {
        sessionKey = value
        val mk = masterKey() ?: return false
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, mk)
            val iv = cipher.iv
            val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val out = ByteArray(iv.size + ct.size)
            System.arraycopy(iv, 0, out, 0, iv.size)
            System.arraycopy(ct, 0, out, iv.size, ct.size)
            file(context).writeBytes(out)
            true
        } catch (t: Throwable) {
            AppLog.e(TAG, "save failed: ${t.javaClass.simpleName}")
            false
        }
    }

    fun load(context: Context): String? {
        sessionKey?.let { return it }
        val f = file(context)
        if (!f.exists()) return null
        val mk = masterKey() ?: return null
        return try {
            val blob = f.readBytes()
            if (blob.size <= IV_LEN) return null
            val iv = blob.copyOfRange(0, IV_LEN)
            val ct = blob.copyOfRange(IV_LEN, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, mk, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (t: Throwable) {
            AppLog.w(TAG, "load failed (key rotated or device reset?) — clearing")
            clear(context)
            null
        }
    }

    fun clear(context: Context) {
        sessionKey = null
        try { file(context).delete() } catch (ignore: Throwable) {}
    }
}
