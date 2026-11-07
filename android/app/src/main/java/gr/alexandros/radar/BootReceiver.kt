package gr.alexandros.radar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts tracking after the phone reboots or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TrackerService.start(context)
    }
}
