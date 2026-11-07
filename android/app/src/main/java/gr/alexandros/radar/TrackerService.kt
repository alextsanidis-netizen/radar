package gr.alexandros.radar

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONObject

class TrackerService : Service() {

    companion object {
        const val ACTION_STOP_RING = "gr.alexandros.radar.STOP_RING"
        private const val CH = "radar"
        private const val NID = 7
        private const val TAG = "Radar"

        fun hasLocation(c: Context) =
            c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun start(c: Context) {
            if (Prefs.token(c) == null || !hasLocation(c)) return
            try {
                c.startForegroundService(Intent(c, TrackerService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "start failed", e)
            }
        }
    }

    private lateinit var thread: HandlerThread
    private lateinit var h: Handler
    private lateinit var fused: FusedLocationProviderClient
    private var lost = false
    private var lastSent = 0L
    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var vib: Vibrator? = null
    private var wake: PowerManager.WakeLock? = null

    private val cb = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            r.lastLocation?.let { send(it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!startFg()) { stopSelf(); return }
        thread = HandlerThread("radar").also { it.start() }
        h = Handler(thread.looper)
        fused = LocationServices.getFusedLocationProviderClient(this)
        lost = Prefs.lost(this)
        h.post { startUpdates(); locateNow() }
        h.postDelayed(pollLoop, 3000)
        h.postDelayed(heartbeat, 10 * 60_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_RING && ::h.isInitialized) h.post { stopRing() }
        return START_STICKY
    }

    override fun onDestroy() {
        if (::h.isInitialized) {
            h.removeCallbacksAndMessages(null)
            try { fused.removeLocationUpdates(cb) } catch (_: Exception) {}
            stopRing()
            thread.quitSafely()
        }
        super.onDestroy()
    }

    // ---------- foreground notification ----------
    private fun notif(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, "Radar", NotificationManager.IMPORTANCE_MIN))
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (lost) "Λειτουργία απώλειας ενεργή" else "Η τοποθεσία μοιράζεται με την ομάδα σου"
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Radar · ${Prefs.name(this)}")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun startFg(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NID, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NID, notif())
        }
        true
    } catch (e: Exception) {
        Log.w(TAG, "startForeground failed", e)
        false
    }

    // ---------- location ----------
    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        if (!hasLocation(this)) return
        try { fused.removeLocationUpdates(cb) } catch (_: Exception) {}
        val req = if (lost) {
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15_000L)
                .setMinUpdateIntervalMillis(10_000L)
                .build()
        } else {
            LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 180_000L)
                .setMinUpdateIntervalMillis(60_000L)
                .setMinUpdateDistanceMeters(30f)
                .build()
        }
        fused.requestLocationUpdates(req, cb, thread.looper)
    }

    @SuppressLint("MissingPermission")
    private fun locateNow() {
        if (!hasLocation(this)) { send(null); return }
        try {
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc -> h.post { send(loc) } }
                .addOnFailureListener { h.post { send(null) } }
        } catch (e: Exception) {
            send(null)
        }
    }

    private fun battery(): Pair<Int, Boolean> {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) to bm.isCharging
    }

    /** Runs on the worker thread. loc == null just refreshes battery / last seen. */
    private fun send(loc: Location?) {
        val token = Prefs.token(this) ?: return
        try {
            val (bat, chg) = battery()
            val body = JSONObject()
                .put("p_token", token)
                .put("p_lat", loc?.latitude ?: JSONObject.NULL)
                .put("p_lng", loc?.longitude ?: JSONObject.NULL)
                .put("p_accuracy", loc?.accuracy?.toDouble() ?: JSONObject.NULL)
                .put("p_battery", bat)
                .put("p_charging", chg)
            val r = JSONObject(Api.rpc("report_location", body))
            lastSent = System.currentTimeMillis()
            setLost(r.optBoolean("lost_mode", lost))
        } catch (e: ApiException) {
            Log.w(TAG, "report: ${e.message}")
            if (e.message?.contains("unknown device") == true) unpaired()
        } catch (e: Exception) {
            Log.w(TAG, "report failed (offline?)", e)
        }
    }

    // ---------- commands ----------
    private val pollLoop = object : Runnable {
        override fun run() {
            val token = Prefs.token(this@TrackerService)
            if (token != null) {
                try {
                    val r = JSONObject(Api.rpc("poll_commands", JSONObject().put("p_token", token)))
                    setLost(r.optBoolean("lost_mode", lost))
                    val arr = r.optJSONArray("commands")
                    if (arr != null) for (i in 0 until arr.length()) handle(arr.getString(i))
                } catch (e: ApiException) {
                    if (e.message?.contains("unknown device") == true) { unpaired(); return }
                } catch (e: Exception) {
                    Log.w(TAG, "poll failed (offline?)")
                }
            }
            h.postDelayed(this, if (lost) 10_000L else 30_000L)
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() - lastSent > 9 * 60_000L) send(null)
            h.postDelayed(this, 10 * 60_000L)
        }
    }

    private fun handle(cmd: String) {
        when (cmd) {
            "ring" -> ring()
            "locate", "lost_on", "lost_off" -> locateNow()
        }
    }

    private fun setLost(v: Boolean) {
        if (v == lost) return
        lost = v
        Prefs.setLost(this, v)
        startUpdates()
        getSystemService(NotificationManager::class.java).notify(NID, notif())
    }

    private fun unpaired() {
        Prefs.clear(this)
        stopSelf()
    }

    // ---------- ring ----------
    @SuppressLint("WakelockTimeout")
    private fun ring() {
        stopRing()
        try {
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "radar:ring").apply { acquire(35_000L) }
        } catch (_: Exception) {}
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: Exception) {}
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@TrackerService, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "alarm sound failed, using tone", e)
            try {
                tone = ToneGenerator(AudioManager.STREAM_ALARM, 100).apply {
                    startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 30_000)
                }
            } catch (_: Exception) {}
        }
        try {
            vib =(getSystemService(VIBRATOR_SERVICE) as Vibrator).apply {
                vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0))
            }
        } catch (_: Exception) {}
        h.postDelayed({ stopRing() }, 30_000L)
    }

    private fun stopRing() {
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        try { tone?.release() } catch (_: Exception) {}
        tone = null
        try { vib?.cancel() } catch (_: Exception) {}
        vib = null
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) {}
        wake = null
    }
}
