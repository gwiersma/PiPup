> **This fork (gwiersma/PiPup)** brings the 2019 app up to current Android and security standards. What changed,
> and why:
>
> - **Authentication.** Every request needs the shared token (`Authorization: Bearer <token>`, or the bare token),
>   compared in constant time. No token configured means every request is refused, so a fresh install is not an
>   open overlay on the LAN. An optional IPv4 allowlist (CIDR) narrows the sources further.
> - **Configuration over ADB only**, through a receiver guarded by `WRITE_SECURE_SETTINGS` (see below).
> - **No multipart uploads.** NanoHTTPD writes those to temporary files (CVE-2022-21230); JSON only, at most
>   32 KiB, read in full.
> - **No Jackson, Glide or support libraries** (unmaintained, with known CVEs in the 2.9 Jackson line): JSON is the
>   platform's `org.json`, images are one bounded https download. The APK went from 5 MB to about 110 KB.
> - **Media URIs are checked:** `https` (and `rtsp` for video, `data:` for web); never `file:`, `content:` or
>   cleartext `http:`. The WebView runs without JavaScript, file or content access, and cannot navigate away.
> - **Validated input:** durations, sizes and colours are clamped or rejected with a 400.
> - **Nothing sensitive in logcat:** only the popup's type, position and duration, never texts or URLs (a camera URL
>   can carry an access token).
> - **Android 14:** targetSdk 34, `specialUse` foreground service, immutable PendingIntent, no exported service,
>   no backup, overlay that never takes focus or touches. Builds with AGP 8.5, Gradle 8.9, Kotlin 2.0, JDK 17.
>
> Upstream has no licence file and this fork adds none, so the original author's rights apply unchanged.

# PiPup

PiPup is an application that allows displaying user-defined custom notifications on Android TV.

The most common use-case for this application is for sending notifications, from a home-automation solution, to your Android TV.

![](https://github.com/rogro82/PiPup/raw/master/graphics/screenshot-1.png)

__Some example scenarios:__

- Show a snapshot of your camera on your TV (eg on a motion trigger)
- Display a notification with the video of your camera when someone is at your door
- Send a notification when your dryer/washingmachine is ready
- Anything else you might find useful


__The application is currently in a `public beta`__

To enter the `beta` and install the application on your device go to:  
https://play.google.com/apps/testing/nl.rogro82.pipup

_Important: after installation / updating it is currently adviced to restart your TV and open the application once to make sure the background-service is running_

#### Sideloading:

On Android TV (8.0+), when sideloading, you will need to set the permission for SYSTEM_ALERT_WINDOW manually (using adb) as there is no interface on Android TV to do this.

To give the application the required permission to draw overlays you will need to run:
```
adb shell appops set nl.rogro82.pipup SYSTEM_ALERT_WINDOW allow
```

Then set the token (24 characters or more; keep it in a file so it stays out of your shell history) and, if you
like, the networks allowed to send popups. The result comes back as the broadcast's data, never the token:
```
adb shell am broadcast -n nl.rogro82.pipup/.ConfigReceiver -a nl.rogro82.pipup.CONFIGURE \
    --es token "$(cat token.txt)" --es allow 10.10.10.0/24
```
`--es allow ""` allows any source; `--ez clear true` removes both, after which every request is refused.

Requests answer `401` without the right token, `403` from outside the allowlist, `503` while no token is set,
`413` over 32 KiB and `400` with a reason for anything invalid.

## Integrating

PiPup uses an embedded webserver (NanoHTTPD) which runs on port 7979.

### Sending notifications

#### To send notifications with an external media resource (image, url or webview) use application/json


| Property      | Value            |
| ------------- | ---------------- |
| Path:         | /notify          |
| Method:       | POST             |
| Content-Type: | application/json |

Example json data:

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
All fields are optional and for `media` you can specify 3 types:

```json 
{ "image": { "uri": "address_to_your_image", "width": 480 }}
{ "video": { "uri": "address_to_your_video", "width": 480 }}
{ "web":   { "uri": "address_to_your_resource", "width": 640, "height": 480 }}
```

`position` is an enum ranging from 0 to 4

|  | Position    |
| -----: | ----------- |
| 0     | TopRight    |
| 1     | TopLeft     |
| 2     | BottomRight |
| 3     | BottomLeft  |
| 4     | Center      |

Color-properties are in `[AA]RRGGBB` where the alpha channel is optional e.g. #FFFFFF or #CCFFFFFF