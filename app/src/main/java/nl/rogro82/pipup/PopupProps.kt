package nl.rogro82.pipup

import android.graphics.Color
import android.net.Uri
import org.json.JSONException
import org.json.JSONObject

/**
 * One popup, parsed from the request body with the platform's org.json (no reflection-based mapper, so no
 * polymorphic-deserialisation surface). Every field is checked and clamped here; [parse] throws
 * [IllegalArgumentException] with a message the client gets back as a 400.
 *
 * The JSON shape is the one upstream PiPup documents, minus multipart uploads.
 */
data class PopupProps(
    val duration: Int,
    val position: Position,
    val backgroundColor: Int,
    val title: String?,
    val titleSize: Float,
    val titleColor: Int,
    val message: String?,
    val messageSize: Float,
    val messageColor: Int,
    val media: Media?
) {
    sealed class Media {
        abstract val uri: Uri

        data class Video(override val uri: Uri, val width: Int) : Media()
        data class Image(override val uri: Uri, val width: Int) : Media()
        data class Web(override val uri: Uri, val width: Int, val height: Int) : Media()
    }

    enum class Position { TopRight, TopLeft, BottomRight, BottomLeft, Center }

    /** For the log: what kind of popup, never its texts or URLs (a camera URL can carry an access token). */
    fun summary() = "popup ${media?.javaClass?.simpleName ?: "Text"} at $position for ${duration}s"

    companion object {
        const val MAX_BODY_BYTES = 32 * 1024
        private const val MAX_TEXT = 500
        private const val MAX_URI = 16 * 1024    // a data: page for a camera stream is a few hundred bytes

        // Schemes per media type. No file: or content: anywhere (local files), no plain http: (the app forbids
        // cleartext). data: is for the web type only, where it is rendered without JavaScript.
        private val VIDEO_SCHEMES = setOf("https", "rtsp")
        private val IMAGE_SCHEMES = setOf("https")
        private val WEB_SCHEMES = setOf("https", "data")

        fun parse(body: String): PopupProps {
            val json = try {
                JSONObject(body)
            } catch (e: JSONException) {
                throw IllegalArgumentException("body is not a JSON object")
            }
            return PopupProps(
                duration = json.optInt("duration", 30).coerceIn(1, 300),
                position = Position.values().getOrNull(json.optInt("position", 0))
                    ?: throw IllegalArgumentException("position must be 0-4"),
                backgroundColor = color(json, "backgroundColor", "#CC000000"),
                title = text(json, "title"),
                titleSize = json.optDouble("titleSize", 16.0).toFloat().coerceIn(6f, 72f),
                titleColor = color(json, "titleColor", "#ffffff"),
                message = text(json, "message"),
                messageSize = json.optDouble("messageSize", 12.0).toFloat().coerceIn(6f, 72f),
                messageColor = color(json, "messageColor", "#ffffff"),
                media = media(json.optJSONObject("media"))
            )
        }

        private fun text(json: JSONObject, key: String): String? =
            json.optString(key, "").takeIf { it.isNotEmpty() }?.take(MAX_TEXT)

        private fun color(json: JSONObject, key: String, default: String): Int {
            val s = json.optString(key, default)
            return try {
                Color.parseColor(s)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("$key is not a colour: use #RRGGBB or #AARRGGBB")
            }
        }

        private fun media(json: JSONObject?): Media? {
            if (json == null) return null
            json.optJSONObject("video")?.let {
                return Media.Video(uri(it, VIDEO_SCHEMES), size(it, "width", 480))
            }
            json.optJSONObject("image")?.let {
                return Media.Image(uri(it, IMAGE_SCHEMES), size(it, "width", 480))
            }
            json.optJSONObject("web")?.let {
                return Media.Web(uri(it, WEB_SCHEMES), size(it, "width", 640), size(it, "height", 480))
            }
            throw IllegalArgumentException("media must hold one of video, image or web")
        }

        private fun uri(json: JSONObject, schemes: Set<String>): Uri {
            val s = json.optString("uri", "")
            if (s.isEmpty() || s.length > MAX_URI) throw IllegalArgumentException("media uri missing or too long")
            val uri = Uri.parse(s)
            val scheme = uri.scheme?.lowercase()
            if (scheme !in schemes) throw IllegalArgumentException("media uri scheme must be one of ${schemes.joinToString()}")
            return uri
        }

        private fun size(json: JSONObject, key: String, default: Int) = json.optInt(key, default).coerceIn(32, 1920)
    }
}
