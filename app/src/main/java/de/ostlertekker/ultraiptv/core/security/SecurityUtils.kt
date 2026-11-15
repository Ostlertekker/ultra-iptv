package de.ostlertekker.ultraiptv.core.security

import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PinHasher {
    private const val ITERATIONS = 12_000

    fun newSalt(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()

    fun hash(pin: String, salt: String): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt.hexToBytes(), ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded.toHex()
    }

    fun verify(pin: String, salt: String?, expected: String?): Boolean {
        if (salt == null || expected == null) return true
        return MessageDigest.isEqual(hash(pin, salt).toByteArray(), expected.toByteArray())
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

object InputValidator {
    /** Liefert eine Fehlermeldung oder null, wenn gültig. */
    fun url(value: String): String? {
        val t = value.trim()
        if (t.isEmpty()) return "Adresse fehlt"
        if (t.any { it.isWhitespace() }) return "Adresse enthält Leerzeichen"
        val withScheme = if ("://" in t) t else "http://$t"
        val uri = try { URI(withScheme) } catch (e: Exception) { return "Ungültige Adresse" }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return "Nur http:// oder https:// erlaubt"
        if (uri.host.isNullOrBlank()) return "Servername fehlt"
        return null
    }

    fun credential(value: String, label: String): String? = when {
        value.isBlank() -> "$label fehlt"
        value.length > 256 -> "$label ist zu lang"
        value.any { it.isISOControl() } -> "$label enthält ungültige Zeichen"
        else -> null
    }

    fun pin(value: String): String? = if (value.length == 4 && value.all { it.isDigit() }) null else "PIN muss aus 4 Ziffern bestehen"

    fun profileName(value: String): String? = when {
        value.isBlank() -> "Name fehlt"
        value.length > 24 -> "Name ist zu lang (max. 24 Zeichen)"
        else -> null
    }
}
