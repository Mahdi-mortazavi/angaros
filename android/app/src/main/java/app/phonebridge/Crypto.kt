package app.phonebridge

import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM, blob = base64(nonce || ciphertext || tag). Same as pc/phone.py; see shared/PROTOCOL.md. */
object Crypto {
    private val rnd = SecureRandom()

    fun seal(key: ByteArray, aad: String, plain: ByteArray, nonce: ByteArray = ByteArray(12).also { rnd.nextBytes(it) }): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad.toByteArray())
        return Base64.getEncoder().encodeToString(nonce + c.doFinal(plain))
    }

    fun open(key: ByteArray, aad: String, blob: String): ByteArray {
        val raw = Base64.getDecoder().decode(blob)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw, 0, 12))
        c.updateAAD(aad.toByteArray())
        return c.doFinal(raw, 12, raw.size - 12)
    }
}

class Pairing(val worker: String, val token: String, val key: ByteArray) {
    val device: String get() = token.substringBefore('.')

    companion object {
        fun parse(s: String): Pairing {
            val t = s.trim().replace(Regex("\\s"), "")
            require(t.startsWith("pb1:")) { "not an ${BuildConfig.BRAND} pairing code" }
            val j = JSONObject(String(Base64.getUrlDecoder().decode(t.removePrefix("pb1:"))))
            val p = Pairing(j.getString("u").trimEnd('/'), j.getString("t"), Base64.getDecoder().decode(j.getString("k")))
            require(p.key.size == 32 && p.token.contains('.') && p.worker.startsWith("http")) { "damaged pairing code" }
            return p
        }
    }
}
