package eu.kanade.tachiyomi.animeextension.all.pikpak

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts session tokens before they are written to shared preferences.
 *
 * The AES key lives in the Android Keystore and never leaves it, so a preferences backup does not
 * expose the PikPak session. Values written by older versions are plain text and are read as-is
 * until the next save re-encrypts them.
 */
internal object TokenCipher {
    private const val KEY_ALIAS = "pikpak_session_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val PREFIX = "v1:"
    private const val SEPARATOR = ":"

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    /** Envelope used for stored values: `v1:<base64 iv>:<base64 ciphertext>`. */
    fun encode(iv: ByteArray, payload: ByteArray): String = PREFIX + iv.toByteString().base64() + SEPARATOR + payload.toByteString().base64()

    fun decodePayload(value: String): Pair<ByteArray, ByteArray>? {
        if (!isEncrypted(value)) return null

        val body = value.removePrefix(PREFIX)
        val split = body.indexOf(SEPARATOR)
        if (split <= 0) return null

        val iv = body.substring(0, split).decodeBase64()?.toByteArray() ?: return null
        val payload = body.substring(split + 1).decodeBase64()?.toByteArray() ?: return null
        return iv to payload
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return plain

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return encode(cipher.iv, cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    /** Returns the plain value; legacy unencrypted values are returned unchanged. */
    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return stored
        if (!isEncrypted(stored)) return stored

        val (iv, payload) = decodePayload(stored) ?: throw IllegalArgumentException("Malformed encrypted value")

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(payload), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEY_STORE).apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEY_STORE)
        val specification = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        generator.init(specification)
        return generator.generateKey()
    }

    private const val KEY_STORE = "AndroidKeyStore"
}
