package nl.rogro82.pipup

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

// Sizes in the request are pixels, as upstream documents them.

@SuppressLint("ViewConstructor")
sealed class PopupView(context: Context, val popup: PopupProps) : LinearLayout(context) {

    protected val frame: FrameLayout

    init {
        inflate(context, R.layout.popup, this)
        orientation = VERTICAL
        minimumWidth = 240
        setPadding(20, 20, 20, 20)
        setBackgroundColor(popup.backgroundColor)

        frame = findViewById(R.id.popup_frame)
        val title = findViewById<TextView>(R.id.popup_title)
        val message = findViewById<TextView>(R.id.popup_message)

        if (popup.media == null) removeView(frame)
        if (popup.title == null) {
            removeView(title)
        } else {
            title.text = popup.title
            title.textSize = popup.titleSize
            title.setTextColor(popup.titleColor)
        }
        if (popup.message == null) {
            removeView(message)
        } else {
            message.text = popup.message
            message.textSize = popup.messageSize
            message.setTextColor(popup.messageColor)
        }
    }

    open fun destroy() {}

    private class Text(context: Context, popup: PopupProps) : PopupView(context, popup)

    private class Video(context: Context, popup: PopupProps, media: PopupProps.Media.Video) : PopupView(context, popup) {
        private val videoView = VideoView(context)

        init {
            visibility = View.INVISIBLE
            videoView.setVideoURI(media.uri)
            videoView.setOnPreparedListener { player ->
                player.setOnVideoSizeChangedListener { _, _, _ ->
                    videoView.layoutParams = FrameLayout.LayoutParams(media.width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
                    visibility = View.VISIBLE
                }
            }
            videoView.setOnErrorListener { _, what, _ ->
                Log.w(LOG_TAG, "video failed ($what)")
                removeView(frame)
                visibility = View.VISIBLE
                true
            }
            frame.addView(videoView, FrameLayout.LayoutParams(1, 1))
            videoView.start()
        }

        override fun destroy() {
            videoView.stopPlayback()
        }
    }

    /** Glide used to do this; a single bounded download is all a popup needs. */
    private class Image(context: Context, popup: PopupProps, media: PopupProps.Media.Image) : PopupView(context, popup) {
        private val imageView = ImageView(context)
        private val main = Handler(Looper.getMainLooper())
        @Volatile private var destroyed = false
        private var bitmap: Bitmap? = null

        init {
            frame.addView(imageView, FrameLayout.LayoutParams(media.width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            Thread({
                val decoded = try {
                    download(media.uri.toString(), media.width)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "image not loaded: ${e.javaClass.simpleName}")
                    null
                }
                main.post {
                    if (destroyed) {
                        decoded?.recycle()
                    } else if (decoded == null) {
                        removeView(frame)
                    } else {
                        bitmap = decoded
                        imageView.setImageBitmap(decoded)
                    }
                }
            }, "pipup-image").start()
        }

        override fun destroy() {
            destroyed = true
            imageView.setImageDrawable(null)
            bitmap?.recycle()
            bitmap = null
        }

        private fun download(url: String, width: Int): Bitmap? {
            val connection = URL(url).openConnection() as HttpsURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false   // a redirect could leave https for somewhere unexpected
            try {
                if (connection.responseCode != 200) return null
                val out = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        if (out.size() > MAX_IMAGE_BYTES) throw IllegalStateException("image over ${MAX_IMAGE_BYTES / 1024 / 1024} MB")
                    }
                }
                val bytes = out.toByteArray()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= width) sample *= 2
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * Locked down: no JavaScript, no file or content access, no mixed content, and no navigation after the first
     * load, so a popup cannot turn into a browser. An MJPEG camera stream in an <img> needs none of it.
     */
    private class Web(context: Context, popup: PopupProps, media: PopupProps.Media.Web) : PopupView(context, popup) {
        private val webView = WebView(context)

        init {
            with(webView.settings) {
                javaScriptEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                setGeolocationEnabled(false)
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                loadWithOverviewMode = true
                useWideViewPort = true
                safeBrowsingEnabled = true
            }
            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            }
            frame.addView(webView, FrameLayout.LayoutParams(media.width, media.height, Gravity.CENTER))
            webView.loadUrl(media.uri.toString())
        }

        override fun destroy() {
            webView.stopLoading()
            frame.removeView(webView)
            webView.destroy()
        }
    }

    companion object {
        const val LOG_TAG = "PopupView"
        private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024

        fun build(context: Context, popup: PopupProps): PopupView = when (val media = popup.media) {
            is PopupProps.Media.Web -> Web(context, popup, media)
            is PopupProps.Media.Video -> Video(context, popup, media)
            is PopupProps.Media.Image -> Image(context, popup, media)
            null -> Text(context, popup)
        }
    }
}
