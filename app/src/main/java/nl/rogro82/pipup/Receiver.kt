package nl.rogro82.pipup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the service after boot. */
class Receiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        context.startForegroundService(Intent(context, PiPupService::class.java))
    }
}
