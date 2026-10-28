package nl.rogro82.pipup

import android.content.Context
import java.security.MessageDigest

/**
 * Who may show popups: a shared bearer token and, optionally, a list of source networks. Both live in the app's
 * private preferences and are set over ADB only (see [ConfigReceiver]); there is no way to set them from the network.
 *
 * Secure by default: until a token is set every request is refused, so a fresh install is not an open overlay
 * on the LAN.
 */
object Config {
    private const val PREFS = "pipup"
    private const val KEY_TOKEN = "token"
    private const val KEY_ALLOW = "allow"
    const val MIN_TOKEN_LENGTH = 24

    fun token(context: Context): String? =
        prefs(context).getString(KEY_TOKEN, null)?.takeIf { it.length >= MIN_TOKEN_LENGTH }

    fun allowList(context: Context): List<Cidr> =
        prefs(context).getString(KEY_ALLOW, "").orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { Cidr.parse(it) }

    fun allowListText(context: Context): String = prefs(context).getString(KEY_ALLOW, "").orEmpty()

    fun setToken(context: Context, token: String?) = prefs(context).edit().putString(KEY_TOKEN, token).commit()

    fun setAllowList(context: Context, list: String?) = prefs(context).edit().putString(KEY_ALLOW, list).commit()

    /** Constant time, so the response time says nothing about how much of a guessed token was right. */
    fun tokenMatches(expected: String, authorization: String?): Boolean {
        val given = authorization?.removePrefix("Bearer ")?.trim() ?: return false
        return MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())
    }

    /** An empty allowlist allows any source (the token still applies). */
    fun sourceAllowed(allow: List<Cidr>, remote: String?): Boolean {
        if (allow.isEmpty()) return true
        val address = remote?.let { Cidr.ipv4(it) } ?: return false
        return allow.any { it.contains(address) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** An IPv4 network such as 10.10.10.0/24 (a bare address means /32). */
class Cidr private constructor(private val network: Int, private val mask: Int, private val text: String) {
    fun contains(address: Int) = (address and mask) == network

    override fun toString() = text

    companion object {
        fun parse(s: String): Cidr? {
            val parts = s.split('/')
            if (parts.size > 2) return null
            val address = ipv4(parts[0]) ?: return null
            val bits = if (parts.size == 2) parts[1].toIntOrNull() ?: return null else 32
            if (bits !in 0..32) return null
            val mask = if (bits == 0) 0 else (-1 shl (32 - bits))
            return Cidr(address and mask, mask, s)
        }

        /** Dotted quad (also IPv4-mapped IPv6, as NanoHTTPD may report it) to an int; null for anything else. */
        fun ipv4(s: String): Int? {
            val plain = s.removePrefix("::ffff:").removePrefix("/")
            val octets = plain.split('.')
            if (octets.size != 4 || octets.any { o -> o.isEmpty() || o.length > 3 || o.any { !it.isDigit() } }) return null
            val bytes = octets.map { it.toInt() }
            if (bytes.any { it > 255 }) return null
            return bytes.fold(0) { acc, b -> (acc shl 8) or b }
        }
    }
}
