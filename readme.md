# PiPup

PiPup shows your own notifications on Android TV, over whatever is playing: a title, a message and optionally a
picture, a video or a small web page, sent to the TV with one HTTP request. The usual sender is a home-automation
system.

![](https://github.com/rogro82/PiPup/raw/master/graphics/screenshot-1.png)

__Some example scenarios:__

- Show a snapshot of your camera on your TV (eg on a motion trigger)
- Display a notification with the video of your camera when someone is at your door
- Send a notification when your dryer/washingmachine is ready
- Anything else you might find useful

## About this fork

PiPup was written by [rogro82](https://github.com/rogro82/pipup) in 2019. This fork, **gwiersma/PiPup**, brings it
up to current Android and security standards. What changed, and why:

- **Authentication.** Every request needs the shared token (`Authorization: Bearer <token>`, or the bare token),
  compared in constant time. No token configured means every request is refused, so a fresh install is not an
  open overlay on the LAN. An optional IPv4 allowlist (CIDR) narrows the sources further.
- **Configuration over ADB only**, through a receiver guarded by `WRITE_SECURE_SETTINGS` (see below).
- **No multipart uploads.** NanoHTTPD writes those to temporary files (CVE-2022-21230); JSON only, at most
  32 KiB, read in full.
- **No Jackson, Glide or support libraries** (unmaintained, with known CVEs in the 2.9 Jackson line): JSON is the
  platform's `org.json`, images are one bounded https download. The APK went from 5 MB to about 110 KB.
- **Media URIs are checked:** `https` (and `rtsp` for video, `data:` for web); never `file:`, `content:` or
  cleartext `http:`. The WebView runs without JavaScript, file or content access, and cannot navigate away.
- **Validated input:** durations, sizes and colours are clamped or rejected with a 400.
- **Nothing sensitive in logcat:** only the popup's type, position and duration, never texts or URLs (a camera URL
  can carry an access token).
- **Android 14:** targetSdk 34, `specialUse` foreground service, immutable PendingIntent, no exported service,
  no backup, an overlay that never takes focus or touches. Builds with AGP 8.5, Gradle 8.9, Kotlin 2.0, JDK 17.
- **Its own application id**, `io.github.gwiersma.pipup`. Upstream's Play Store beta is `nl.rogro82.pipup`,
  signed with another key and without any of the above; the two install side by side and are not updates of one
  another. Uninstall the old one.

Upstream has no licence file and this fork adds none, so the original author's rights apply unchanged.

## Install

Android TV 8.0 or newer. Download the APK from [Releases](https://github.com/gwiersma/PiPup/releases), check it,
and install it over ADB:

```
sha256sum -c PiPup-<version>.apk.sha256
apksigner verify --print-certs PiPup-<version>.apk   # from the Android SDK build-tools
adb install PiPup-<version>.apk
```

Every release is signed with the same key, certificate SHA-256
`ed4afc04638ab4a43b7667d70f4494820785233688dd3a0c8f88b762d0b834e7`. An APK with any other certificate is not
from this fork: don't install it. (On Android 14, `adb install` can wait for a confirmation on the TV; if it
hangs, `adb push` the APK and run `adb shell pm install -r /data/local/tmp/<file>.apk` instead.)

Android TV has no screen for the overlay permission, so grant it over ADB:

```
adb shell appops set io.github.gwiersma.pipup SYSTEM_ALERT_WINDOW allow
```

Open PiPup once from the app list: that starts the service, and from then on it starts with the TV.

## Configure

Set the token (24 characters or more, no spaces) and, if you like, the networks allowed to send popups. Keep the
token in a file, so it stays out of your shell history; the result comes back as the broadcast's data, never
the token:

```
adb shell am broadcast -n io.github.gwiersma.pipup/nl.rogro82.pipup.ConfigReceiver \
    -a nl.rogro82.pipup.CONFIGURE --es token "$(cat token.txt)" --es allow 192.168.1.0/24
```

`--es allow ""` allows any source; `--ez clear true` removes both, after which every request is refused. The app
shows on its own screen whether a token is set and which networks are allowed, never the token. Turn ADB off
again when you are done.

Requests answer `401` without the right token, `403` from outside the allowlist, `503` while no token is set,
`413` over 32 KiB and `400` with a reason for anything invalid.

## Sending notifications

PiPup listens on port 7979.

| Property       | Value                   |
| -------------- | ----------------------- |
| Path           | `/notify`               |
| Method         | `POST`                  |
| Content-Type   | `application/json`      |
| Authorization  | `Bearer <token>`        |

`POST /cancel` (same header) removes the popup on screen.

Example body:

```json
{
  "duration": 30,
  "position": 0,
  "title": "Your awesome title",
  "titleColor": "#0066cc",
  "titleSize": 20,
  "message": "What ever you want to say... do it here...",
  "messageColor": "#000000",
  "messageSize": 14,
  "backgroundColor": "#ffffff",
  "media": { "image": {
    "uri": "https://mir-s3-cdn-cf.behance.net/project_modules/max_1200/cfcc3137009463.5731d08bd66a1.png", "width": 480
  }}
}
```

All fields are optional. `duration` is 1 to 300 seconds (default 30), text sizes 6 to 72, media sizes 32 to 1920
pixels. For `media` you can specify one of three types:

```json
{ "image": { "uri": "https://…", "width": 480 }}
{ "video": { "uri": "https://… or rtsp://…", "width": 480 }}
{ "web":   { "uri": "https://… or data:text/html,…", "width": 640, "height": 480 }}
```

An image is downloaded once (at most 5 MB, no redirects). A web page is shown without JavaScript; to show an
MJPEG camera stream, wrap it in an `<img>` on a `data:` page, as in the Home Assistant example below.

`position` is an enum ranging from 0 to 4

|  | Position    |
| -----: | ----------- |
| 0     | TopRight    |
| 1     | TopLeft     |
| 2     | BottomRight |
| 3     | BottomLeft  |
| 4     | Center      |

Color-properties are in `[AA]RRGGBB` where the alpha channel is optional e.g. #FFFFFF or #CCFFFFFF

### Home Assistant

A `rest_command` that shows a camera's live view for 15 seconds, with the token in `secrets.yaml` (store it with
its `Bearer ` prefix, as `!secret` cannot be combined with other text):

```yaml
rest_command:
  tv_popup_camera:
    url: "http://<tv ip>:7979/notify"
    method: POST
    content_type: "application/json"
    headers:
      Authorization: !secret pipup_authorization   # "Bearer <token>"
    payload: >-
      {% set src = 'https://<your home assistant>/api/camera_proxy_stream/camera.front_door?token='
                   ~ state_attr('camera.front_door', 'access_token') %}
      {% set page = '<body style="margin:0;background:#000"><img src="' ~ src
                    ~ '" style="width:100%;height:100%;object-fit:contain"></body>' %}
      {{ {
        'duration': 15,
        'title': 'Someone is at the door',
        'media': {'web': {'uri': 'data:text/html,' ~ (page | urlencode), 'width': 480, 'height': 270}}
      } | to_json }}
```

The camera's `access_token` is short-lived (it rotates every five minutes), so no long-lived Home Assistant
token ends up on the TV. Give the TV a fixed address (a DHCP reservation), or the popup quietly stops arriving.

## Building

```
./gradlew assembleRelease
```

JDK 17 and the Android SDK (platform 34). Without signing settings a release build is signed with your local debug
key, which is fine for your own TV. To sign with your own key, point `PIPUP_KEYSTORE` at the keystore and
`PIPUP_KEYSTORE_PASSWORD_FILE` at a file holding its password (key alias `pipup`).
