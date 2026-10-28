package nl.rogro82.pipup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Sets the token and allowlist. Protected by WRITE_SECURE_SETTINGS in the manifest, which the ADB shell holds and
 * ordinary apps cannot get, so only someone with ADB access to the TV can configure it:
 *
 *   adb shell am broadcast -n nl.rogro82.pipup/.ConfigReceiver -a nl.rogro82.pipup.CONFIGURE \
 *       --es token "$(cat token.txt)" --es allow 10.10.10.0/24
 *
 * The result (never the token) comes back as the broadcast's result data.
 */
class ConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val messages = mutableListOf<String>()
        intent.getStringExtra("token")?.let { token ->
            if (token.length < Config.MIN_TOKEN_LENGTH || token.any { it.isWhitespace() }) {
                messages += "token refused: at least ${Config.MIN_TOKEN_LENGTH} characters, no spaces"
            } else {
                Config.setToken(context, token)
                messages += "token set"
            }
        }
        intent.getStringExtra("allow")?.let { allow ->
            val entries = allow.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val bad = entries.filter { Cidr.parse(it) == null }
            if (bad.isNotEmpty()) {
                messages += "allow refused: not an IPv4 network: ${bad.joinToString()}"
            } else {
                Config.setAllowList(context, entries.joinToString(","))
                messages += "allow set to ${entries.ifEmpty { listOf("any source") }.joinToString()}"
            }
        }
        if (intent.getBooleanExtra("clear", false)) {
            Config.setToken(context, null)
            Config.setAllowList(context, null)
            messages += "cleared: every request is refused until a token is set"
        }
        resultData = messages.ifEmpty { listOf("nothing changed (extras: token, allow, clear)") }.joinToString("; ")
    }

    companion object {
        const val ACTION = "nl.rogro82.pipup.CONFIGURE"
    }
}
