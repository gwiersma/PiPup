package nl.rogro82.pipup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.ByteArrayOutputStream

/**
 * Keeps a small HTTP server on port 7979 and draws the popups it is sent over whatever is on screen.
 *
 * Every request must carry `Authorization: Bearer <token>` and, when an allowlist is set, come from one of its
 * networks (see [Config]). Bodies are JSON only (multipart uploads are gone: NanoHTTPD writes those to temporary
 * files) and capped at [PopupProps.MAX_BODY_BYTES].
 */
class PiPupService : Service(), WebServer.Handler {
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: FrameLayout? = null
    private var popup: PopupView? = null
    private lateinit var server: WebServer

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Service", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while PiPup listens for popups"
            }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("PiPup")
            .setContentText("Listening for popups")
            .setContentIntent(open)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        server = WebServer(SERVER_PORT, this).apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        Log.i(LOG_TAG, "listening on $SERVER_PORT" + if (Config.token(this) == null) " (no token set: every request is refused)" else "")
    }

    override fun onDestroy() {
        server.stop()
        handler.post { removePopup(removeOverlay = true) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    private fun removePopup(removeOverlay: Boolean = false) {
        handler.removeCallbacksAndMessages(null)
        popup?.destroy()
        popup = null
        overlay?.let {
            it.removeAllViews()
            if (removeOverlay) {
                getSystemService(WindowManager::class.java).removeViewImmediate(it)
                overlay = null
            }
        }
    }

    private fun showPopup(props: PopupProps) {
        try {
            removePopup()
            val frame = overlay ?: FrameLayout(this).also {
                it.setPadding(20, 20, 20, 20)
                // Never takes focus or touches: the remote keeps controlling what is playing underneath.
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                )
                getSystemService(WindowManager::class.java).addView(it, params)
                overlay = it
            }
            val view = PopupView.build(this, props)
            popup = view
            frame.addView(view, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                when (props.position) {
                    PopupProps.Position.TopRight -> Gravity.TOP or Gravity.END
                    PopupProps.Position.TopLeft -> Gravity.TOP or Gravity.START
                    PopupProps.Position.BottomRight -> Gravity.BOTTOM or Gravity.END
                    PopupProps.Position.BottomLeft -> Gravity.BOTTOM or Gravity.START
                    PopupProps.Position.Center -> Gravity.CENTER
                }
            ))
            handler.postDelayed({ removePopup(removeOverlay = true) }, props.duration * 1000L)
        } catch (e: Exception) {
            // Most often the overlay permission is missing: appops set nl.rogro82.pipup SYSTEM_ALERT_WINDOW allow
            Log.e(LOG_TAG, "popup not shown: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun handleHttpRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val remote = session.remoteIpAddress
        val token = Config.token(this) ?: return reply(Status.SERVICE_UNAVAILABLE, "no token configured")
        if (!Config.sourceAllowed(Config.allowList(this), remote)) {
            Log.w(LOG_TAG, "refused request from $remote: not in the allowlist")
            return reply(Status.FORBIDDEN, "forbidden")
        }
        if (!Config.tokenMatches(token, session.headers["authorization"])) {
            Log.w(LOG_TAG, "refused request from $remote: missing or wrong token")
            return reply(Status.UNAUTHORIZED, "unauthorized")
        }
        if (session.method != NanoHTTPD.Method.POST) return reply(Status.METHOD_NOT_ALLOWED, "POST only")

        return when (session.uri) {
            "/cancel" -> {
                handler.post { removePopup(removeOverlay = true) }
                reply(Status.OK, "ok")
            }
            "/notify" -> {
                if (session.headers["content-type"]?.startsWith(APPLICATION_JSON) != true) {
                    return reply(Status.UNSUPPORTED_MEDIA_TYPE, "application/json only")
                }
                val body = readBody(session) ?: return reply(Status.PAYLOAD_TOO_LARGE, "body over ${PopupProps.MAX_BODY_BYTES} bytes")
                try {
                    val props = PopupProps.parse(body)
                    Log.i(LOG_TAG, "showing ${props.summary()}")
                    handler.post { showPopup(props) }
                    reply(Status.OK, "ok")
                } catch (e: IllegalArgumentException) {
                    reply(Status.BAD_REQUEST, e.message ?: "invalid popup")
                }
            }
            else -> reply(Status.NOT_FOUND, "unknown path")
        }
    }

    /** The whole body, or null when it is (or claims to be) over the limit. A single read() can return less. */
    private fun readBody(session: NanoHTTPD.IHTTPSession): String? {
        val declared = session.headers["content-length"]?.toLongOrNull() ?: return ""
        if (declared < 0 || declared > PopupProps.MAX_BODY_BYTES) return null
        val out = ByteArrayOutputStream(declared.toInt())
        val buffer = ByteArray(4096)
        var remaining = declared.toInt()
        while (remaining > 0) {
            val n = session.inputStream.read(buffer, 0, minOf(buffer.size, remaining))
            if (n < 0) break
            out.write(buffer, 0, n)
            remaining -= n
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun reply(status: Status, text: String) = NanoHTTPD.newFixedLengthResponse(status, "text/plain", text)

    companion object {
        const val LOG_TAG = "PiPupService"
        const val SERVER_PORT = 7979
        private const val CHANNEL = "service_channel"
        private const val NOTIFICATION_ID = 123
        private const val APPLICATION_JSON = "application/json"
    }
}
