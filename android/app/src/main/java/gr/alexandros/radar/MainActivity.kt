package gr.alexandros.radar

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity() {

    private val bg = Color.parseColor("#0B0B14")
    private val card = Color.parseColor("#161624")
    private val acc = Color.parseColor("#7C5CFF")
    private val muted = Color.parseColor("#9A9AB0")
    private val ok = Color.parseColor("#2ECC71")
    private val bad = Color.parseColor("#FF4D6D")

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(48), dp(22), dp(32))
        }
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.token(this) != null) {
            TrackerService.start(this)
            // opening the app stops a ringing alarm
            try {
                startService(Intent(this, TrackerService::class.java).setAction(TrackerService.ACTION_STOP_RING))
            } catch (_: Exception) {}
        }
        render()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        TrackerService.start(this)
        render()
    }

    // ---------------- UI ----------------
    private fun render() {
        root.removeAllViews()
        val title = text("📡 Radar", 30f, Color.WHITE, bold = true)
        root.addView(title)
        if (Prefs.token(this) == null) renderPair() else renderStatus(title)
    }

    private fun renderPair() {
        root.addView(text("Σύνδεσε αυτή τη συσκευή με την ομάδα σου.\n\nΣτον χάρτη (λάπτοπ ή κινητό) πάτα «＋ Προσθήκη συσκευής» και γράψε εδώ τον 6ψήφιο κωδικό.", 15f, muted).apply { setPadding(0, dp(8), 0, dp(20)) })
        val code = EditText(this).apply {
            hint = "ΚΩΔΙΚΟΣ"
            textSize = 30f
            gravity = Gravity.CENTER
            letterSpacing = 0.3f
            setTextColor(acc)
            setHintTextColor(Color.parseColor("#44445A"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(6), InputFilter.AllCaps())
            background = round(card, 16)
            setPadding(dp(12), dp(16), dp(12), dp(16))
        }
        root.addView(code)
        val btn = button("Σύνδεση", acc)
        btn.setOnClickListener {
            val c = code.text.toString().trim()
            if (c.length < 6) { toast("Γράψε τον 6ψήφιο κωδικό"); return@setOnClickListener }
            btn.isEnabled = false
            btn.text = "Σύνδεση…"
            Thread {
                try {
                    val model = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
                    val r = JSONObject(Api.rpc("pair_device", JSONObject().put("p_code", c).put("p_model", model)))
                    Prefs.save(this, r.getString("token"), r.optString("name"))
                    Prefs.setLost(this, false)
                    runOnUiThread { askLocation(); render() }
                } catch (e: Exception) {
                    runOnUiThread {
                        btn.isEnabled = true; btn.text = "Σύνδεση"
                        toast(e.message ?: "Σφάλμα σύνδεσης")
                    }
                }
            }.start()
        }
        root.addView(btn)
    }

    private fun renderStatus(title: TextView) {
        title.setOnLongClickListener { confirmUnpair(); true }
        root.addView(text("✅ Συνδεδεμένη ως «${Prefs.name(this)}»", 18f, ok, bold = true).apply { setPadding(0, dp(10), 0, dp(4)) })
        root.addView(text("Η τοποθεσία στέλνεται στον χάρτη της ομάδας σου. Για να δουλεύει και σε κλοπή, όλα τα παρακάτω πρέπει να είναι πράσινα.", 14f, muted).apply { setPadding(0, 0, 0, dp(18)) })

        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        step("1. Ακριβής τοποθεσία", fine) { askLocation() }

        val bgLoc = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        step("2. Τοποθεσία «Να επιτρέπεται πάντα»", bgLoc) {
            if (!fine) { toast("Πρώτα το βήμα 1"); return@step }
            AlertDialog.Builder(this)
                .setTitle("Τοποθεσία πάντα")
                .setMessage("Στην επόμενη οθόνη διάλεξε «Να επιτρέπεται πάντα» (Allow all the time).")
                .setPositiveButton("Εντάξει") { _, _ ->
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
                }.show()
        }

        if (Build.VERSION.SDK_INT >= 33) {
            step("3. Ειδοποιήσεις", granted(Manifest.permission.POST_NOTIFICATIONS)) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
            }
        }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        step("4. Μπαταρία: χωρίς περιορισμούς", pm.isIgnoringBatteryOptimizations(packageName)) {
            batteryOpt()
        }

        step("5. Αυτόματη εκκίνηση (Xiaomi / Redmi / POCO)", null) { autostart() }

        root.addView(text("\nΣτα Xiaomi: Ρυθμίσεις → Εφαρμογές → Radar → Αυτόματη εκκίνηση ON και Εξοικονόμηση μπαταρίας → «Χωρίς περιορισμούς». Καλό είναι και να «κλειδώσεις» την εφαρμογή στις πρόσφατες (σύρε προς τα κάτω την κάρτα της).", 13f, muted))
        root.addView(text("\nΑν πατήσει κάποιος «Κάνε ήχο» από τον χάρτη, ανοίγοντας την εφαρμογή σταματάει ο ήχος.", 13f, muted))
    }

    private fun step(label: String, done: Boolean?, action: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(card, 14)
            setPadding(dp(14), dp(12), dp(10), dp(12))
        }
        val icon = when (done) { true -> "🟢"; false -> "🔴"; null -> "⚙️" }
        row.addView(text("$icon  $label", 15f, Color.WHITE).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (done != true) {
            row.addView(button(if (done == null) "Άνοιγμα" else "Ρύθμιση", if (done == false) bad else acc).apply {
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(-2, -2)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                setOnClickListener { action() }
            })
        }
        root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    // ---------------- actions ----------------
    private fun askLocation() {
        val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(list.toTypedArray(), 1)
    }

    @SuppressLint("BatteryLife")
    private fun batteryOpt() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            appSettings()
        }
    }

    private fun autostart() {
        val intents = listOf(
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
        )
        for (i in intents) {
            try { startActivity(i); return } catch (_: Exception) {}
        }
        appSettings()
    }

    private fun appSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun confirmUnpair() {
        AlertDialog.Builder(this)
            .setTitle("Αποσύνδεση συσκευής;")
            .setMessage("Η συσκευή θα σταματήσει να φαίνεται στον χάρτη μέχρι να τη συνδέσεις ξανά με νέο κωδικό.")
            .setNegativeButton("Άκυρο", null)
            .setPositiveButton("Αποσύνδεση") { _, _ ->
                stopService(Intent(this, TrackerService::class.java))
                Prefs.clear(this)
                render()
            }.show()
    }

    // ---------------- helpers ----------------
    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(s: String, color: Int) = Button(this).apply {
        text = s
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 16f
        background = round(color, 14)
        stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
    }.also { it.visibility = View.VISIBLE }
}
