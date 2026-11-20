package com.prash.kosh.sync

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encryption for everything Kosh puts in Google Drive. The web app implements the same format
 * with WebCrypto, so files written by either can be read by the other.
 *
 * key  = PBKDF2-HMAC-SHA256(passphrase, salt, 210 000 iterations, 256 bits)
 * file = "KSY1" | 12-byte IV | AES-256-GCM(gzip(content)), with "KSY1" as additional authenticated data
 */
object SyncCrypto {
    private val MAGIC = "KSY1".toByteArray()
    const val ITERATIONS = 210_000
    const val CHECK_TEXT = "kosh-sync-check-v1"

    class WrongPassphrase : Exception("Wrong sync passphrase")

    fun newSalt(): ByteArray = ByteArray(16).also(SecureRandom()::nextBytes)

    fun deriveKey(passphrase: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, 256)).encoded

    fun encrypt(key: ByteArray, plain: ByteArray, iv: ByteArray = ByteArray(12).also(SecureRandom()::nextBytes)): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(MAGIC)
        return MAGIC + iv + c.doFinal(gzip(plain))
    }

    fun decrypt(key: ByteArray, data: ByteArray): ByteArray {
        require(data.size > 16 && data.copyOfRange(0, 4).contentEquals(MAGIC)) { "Not a Kosh sync file" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data, 4, 12))
        c.updateAAD(MAGIC)
        val plain = try { c.doFinal(data, 16, data.size - 16) } catch (e: javax.crypto.AEADBadTagException) { throw WrongPassphrase() }
        return gunzip(plain)
    }

    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)

    private fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()
    private fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(b.inputStream()).use { it.readBytes() }
}

/** Hybrid logical clock: versions that sort correctly across devices even with clock drift. */
class Hlc(private var lastMillis: Long, private var counter: Int, private val deviceId: String) {
    @Synchronized
    fun next(now: Long = System.currentTimeMillis()): String {
        if (now > lastMillis) { lastMillis = now; counter = 0 } else counter++
        return format(lastMillis, counter, deviceId)
    }

    /** Moves the clock past a version seen from another device. */
    @Synchronized
    fun observe(version: String) {
        val (m, c) = parse(version) ?: return
        if (m > lastMillis || (m == lastMillis && c > counter)) { lastMillis = m; counter = c }
    }

    fun state(): Pair<Long, Int> = lastMillis to counter

    companion object {
        fun format(m: Long, c: Int, d: String) = "%013d-%04d-%s".format(m, c, d)
        fun parse(v: String): Pair<Long, Int>? = v.split('-').takeIf { it.size >= 3 }?.let { p ->
            p[0].toLongOrNull()?.let { m -> p[1].toIntOrNull()?.let { c -> m to c } }
        }
    }
}
