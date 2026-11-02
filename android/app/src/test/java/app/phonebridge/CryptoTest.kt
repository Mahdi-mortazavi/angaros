package app.phonebridge

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Shared vector written by pc/genvec.py: proves Kotlin and Python produce/accept identical bytes. */
class CryptoTest {
    private val v = JSONObject(File(System.getProperty("user.dir"), "../../shared/test-vector.json").readText())
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val key = hex(v.getString("key_hex"))

    @Test fun kotlinSealMatchesPythonBlob() {
        val blob = Crypto.seal(key, v.getString("aad"), v.getString("plaintext").toByteArray(), hex(v.getString("nonce_hex")))
        assertEquals(v.getString("blob"), blob)
    }

    @Test fun kotlinOpensPythonBlob() {
        assertEquals(v.getString("plaintext"), String(Crypto.open(key, v.getString("aad"), v.getString("blob"))))
    }

    @Test fun wrongDirectionIsRejected() {
        try {
            Crypto.open(key, v.getString("aad").replace("|up", "|down"), v.getString("blob"))
            fail("a blob sealed for 'up' must not open as 'down'")
        } catch (e: javax.crypto.AEADBadTagException) { /* expected */ }
    }

    @Test fun pairingStringFromPythonParses() {
        val p = v.getJSONObject("pairing")
        val pr = Pairing.parse(p.getString("string"))
        assertEquals(p.getString("worker"), pr.worker)
        assertEquals(p.getString("token"), pr.token)
        assertEquals("dev123dev123dev123", pr.device)
        assertArrayEquals(key, pr.key)
    }
}
