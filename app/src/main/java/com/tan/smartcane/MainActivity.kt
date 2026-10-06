package com.tan.smartcane

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.SmsManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private enum class St { OFF, CONNECTING, CONNECTED, ERROR }

    // ---------- kulay (Light Clean) ----------
    private val navy = Color.parseColor("#1D3557")
    private val teal = Color.parseColor("#2A9D8F")
    private val red = Color.parseColor("#E63946")
    private val bg = Color.parseColor("#F1FAEE")
    private val gray = Color.parseColor("#6B7785")
    private val border = Color.parseColor("#D5DBE1")
    private val amber = Color.parseColor("#F4A261")
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val sentAction = "com.tan.smartcane.SMS_SENT"
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val maxLog = 12
    private val maxRetry = 5

    private lateinit var prefs: SharedPreferences
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var spinner: Spinner
    private lateinit var btnConnect: Button
    private lateinit var contact: EditText
    private lateinit var latestTitle: TextView
    private lateinit var latestMeta: TextView
    private lateinit var historyBox: LinearLayout
    private lateinit var historyEmpty: TextView

    private var devices = listOf<BluetoothDevice>()
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var connected = false
    @Volatile private var wantConnected = false
    private val connecting = AtomicBoolean(false)
    private var retryCount = 0
    private var currentAddress: String? = null

    private var coords: String? = null
    private var smsRetries = 0
    private var isTest = false

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private var mp: MediaPlayer? = null

    // [grupo][20cm, 50cm, 80cm] - kapareho ng sa Arduino
    private val where = arrayOf("kaliwa", "gitna", "kanan", "kaliwa at gitna", "kanan at gitna", "kanan at kaliwa", "lahat ng direksyon")
    private val table = arrayOf(
        intArrayOf(15, 8, 1), intArrayOf(16, 9, 2), intArrayOf(17, 10, 3), intArrayOf(18, 11, 4),
        intArrayOf(19, 12, 5), intArrayOf(20, 13, 6), intArrayOf(21, 14, 7)
    )
    private val dist = intArrayOf(20, 50, 80)

    // ---------- SMS result ----------
    private val smsResult = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (resultCode == Activity.RESULT_OK) {
                smsRetries = 0
                send("SMS_OK")
                addEntry("sms", "SMS naipadala", "SMS_OK")
            } else {
                send("SMS_FAIL")
                addEntry("sms", "SMS pumalya", "SMS_FAIL")
                if (!isTest && smsRetries < 3) {
                    smsRetries++
                    ui.postDelayed({ sendSms() }, 1500)
                }
            }
        }
    }

    // ================= UI =================
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = dp(radiusDp).toFloat()
        if (stroke != 0) g.setStroke(dp(2), stroke)
        return g
    }

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = rounded(Color.WHITE, border, 16)
        c.setPadding(dp(16), dp(14), dp(16), dp(16))
        val lp = LinearLayout.LayoutParams(match, wrap)
        lp.topMargin = dp(12)
        c.layoutParams = lp
        return c
    }

    private fun sectionTitle(t: String): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = 13f
        v.setTextColor(gray)
        v.typeface = Typeface.DEFAULT_BOLD
        v.letterSpacing = 0.08f
        v.setPadding(0, 0, 0, dp(6))
        if (Build.VERSION.SDK_INT >= 28) v.isAccessibilityHeading = true
        return v
    }

    private fun caption(t: String): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = 14f
        v.setTextColor(gray)
        v.setPadding(0, dp(6), 0, dp(2))
        return v
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.textSize = 18f
        b.typeface = Typeface.DEFAULT_BOLD
        b.setTextColor(if (primary) Color.WHITE else navy)
        val base = rounded(if (primary) navy else Color.WHITE, navy, 14)
        b.background = RippleDrawable(ColorStateList.valueOf(0x33000000), base, null)
        b.stateListAnimator = null
        b.minHeight = dp(56)
        b.minimumHeight = dp(56)
        val lp = LinearLayout.LayoutParams(match, wrap)
        lp.topMargin = dp(10)
        b.layoutParams = lp
        b.setOnClickListener { onClick() }
        return b
    }

    private fun buildUi() {
        // header na may logo
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setBackgroundColor(navy)
        header.setPadding(dp(16), dp(10), dp(16), dp(10))
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.ic_launcher_foreground)
        logo.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        header.addView(logo, LinearLayout.LayoutParams(dp(56), dp(56)))
        val title = TextView(this)
        title.text = "Smart Cane"
        title.textSize = 24f
        title.setTextColor(Color.WHITE)
        title.typeface = Typeface.DEFAULT_BOLD
        title.setPadding(dp(8), 0, 0, 0)
        if (Build.VERSION.SDK_INT >= 28) title.isAccessibilityHeading = true
        header.addView(title)

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(16), dp(4), dp(16), dp(24))

        // --- Koneksyon ---
        val conn = card()
        conn.addView(sectionTitle("KONEKSYON"))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(4), 0, dp(4))
        statusDot = View(this)
        val dot = GradientDrawable()
        dot.shape = GradientDrawable.OVAL
        dot.setColor(gray)
        statusDot.background = dot
        statusDot.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val dotLp = LinearLayout.LayoutParams(dp(14), dp(14))
        dotLp.rightMargin = dp(10)
        row.addView(statusDot, dotLp)
        statusText = TextView(this)
        statusText.text = "Hindi nakakonekta"
        statusText.textSize = 20f
        statusText.setTextColor(navy)
        statusText.typeface = Typeface.DEFAULT_BOLD
        statusText.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        row.addView(statusText)
        conn.addView(row)
        conn.addView(caption("Pumili ng device"))
        spinner = Spinner(this)
        spinner.contentDescription = "Pumili ng Bluetooth device"
        conn.addView(spinner, LinearLayout.LayoutParams(match, dp(52)))
        btnConnect = button("Kumonekta sa Smart Cane", true) { startConnect() }
        conn.addView(btnConnect)
        conn.addView(button("Idiskonekta", false) { disconnectByUser() })
        content.addView(conn)

        // --- Huling alerto ---
        val latest = LinearLayout(this)
        latest.orientation = LinearLayout.VERTICAL
        latest.background = rounded(navy, 0, 18)
        latest.setPadding(dp(18), dp(16), dp(18), dp(16))
        val latestLp = LinearLayout.LayoutParams(match, wrap)
        latestLp.topMargin = dp(12)
        latest.layoutParams = latestLp
        val lbl = TextView(this)
        lbl.text = "HULING ALERTO"
        lbl.textSize = 12f
        lbl.setTextColor(Color.parseColor("#8EE0D4"))
        lbl.typeface = Typeface.DEFAULT_BOLD
        lbl.letterSpacing = 0.1f
        if (Build.VERSION.SDK_INT >= 28) lbl.isAccessibilityHeading = true
        latest.addView(lbl)
        latestTitle = TextView(this)
        latestTitle.text = "Wala pang alerto"
        latestTitle.textSize = 26f
        latestTitle.setTextColor(Color.WHITE)
        latestTitle.typeface = Typeface.DEFAULT_BOLD
        latestTitle.setPadding(0, dp(6), 0, dp(6))
        latest.addView(latestTitle)
        latestMeta = TextView(this)
        latestMeta.text = "Hintayin ang alerto mula sa tungkod"
        latestMeta.textSize = 14f
        latestMeta.setTextColor(Color.parseColor("#A8B5C8"))
        latestMeta.typeface = Typeface.MONOSPACE
        latest.addView(latestMeta)
        content.addView(latest)

        // --- Kasaysayan ---
        val hist = card()
        hist.addView(sectionTitle("KASAYSAYAN"))
        historyEmpty = TextView(this)
        historyEmpty.text = "Wala pang laman."
        historyEmpty.textSize = 15f
        historyEmpty.setTextColor(gray)
        hist.addView(historyEmpty)
        historyBox = LinearLayout(this)
        historyBox.orientation = LinearLayout.VERTICAL
        hist.addView(historyBox)
        content.addView(hist)

        // --- Emergency contact ---
        val cc = card()
        cc.addView(sectionTitle("EMERGENCY CONTACT"))
        contact = EditText(this)
        contact.hint = "Numero ng emergency contact"
        contact.textSize = 20f
        contact.inputType = InputType.TYPE_CLASS_PHONE
        contact.setSingleLine(true)
        contact.minHeight = dp(56)
        contact.setText(prefs.getString("num", ""))
        cc.addView(contact, LinearLayout.LayoutParams(match, wrap))
        cc.addView(button("I-save ang contact", true) { saveContact() })
        cc.addView(button("Test SMS", false) { isTest = true; sendSms() })
        content.addView(cc)

        // --- Pagsubok ---
        val tc = card()
        tc.addView(sectionTitle("PAGSUBOK"))
        tc.addView(button("Test alert sa device", false) { send("TEST") })
        tc.addView(button("Test suara (AirPods)", false) { speakText("Nakakonekta ang Smart Cane", "local") })
        content.addView(tc)

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.addView(content)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(bg)
        root.addView(header, LinearLayout.LayoutParams(match, wrap))
        root.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))
        setContentView(root)
    }

    private fun setStatus(st: St, text: String) {
        ui.post {
            statusText.text = text
            val c = when (st) {
                St.CONNECTED -> teal
                St.CONNECTING -> amber
                St.ERROR -> red
                St.OFF -> gray
            }
            (statusDot.background as GradientDrawable).setColor(c)
        }
    }

    private fun setBusy(busy: Boolean) {
        ui.post {
            btnConnect.isEnabled = !busy
            btnConnect.alpha = if (busy) 0.5f else 1f
        }
    }

    // ================= LOG =================
    private fun addEntry(type: String, title: String, code: String) {
        val time = timeFmt.format(Date())
        ui.post {
            if (type == "audio" || type == "sos") {
                latestTitle.text = title
                latestMeta.text = "$code  |  $time"
            }
            historyEmpty.visibility = View.GONE
            historyBox.addView(makeRow(type, title, code, time), 0)
            while (historyBox.childCount > maxLog) historyBox.removeViewAt(historyBox.childCount - 1)
        }
    }

    private fun makeRow(type: String, title: String, code: String, time: String): View {
        val color = when (type) {
            "audio" -> teal
            "sms" -> navy
            "sos" -> red
            else -> gray
        }
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(7), 0, dp(7))
        row.contentDescription = "$title, $time"
        row.isFocusable = true
        val stripe = View(this)
        stripe.setBackgroundColor(color)
        val stripeLp = LinearLayout.LayoutParams(dp(5), dp(36))
        stripeLp.rightMargin = dp(10)
        row.addView(stripe, stripeLp)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val t1 = TextView(this)
        t1.text = title
        t1.textSize = 16f
        t1.setTextColor(navy)
        col.addView(t1)
        val t2 = TextView(this)
        t2.text = code
        t2.textSize = 12f
        t2.setTextColor(color)
        t2.typeface = Typeface.MONOSPACE
        col.addView(t2)
        row.addView(col, LinearLayout.LayoutParams(0, wrap, 1f))
        val t3 = TextView(this)
        t3.text = time
        t3.textSize = 12f
        t3.setTextColor(gray)
        row.addView(t3)
        return row
    }

    // ================= LIFECYCLE =================
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // pansamantala hangga't walang foreground service
        window.statusBarColor = navy
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        buildUi()

        val f = IntentFilter(sentAction)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(smsResult, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(smsResult, f)

        initTts()

        val p = mutableListOf(Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= 31) p.add(Manifest.permission.BLUETOOTH_CONNECT)
        requestPermissions(p.toTypedArray(), 1)
        loadDevices()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        loadDevices()
    }

    override fun onDestroy() {
        super.onDestroy()
        wantConnected = false
        try { unregisterReceiver(smsResult) } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        try { mp?.release() } catch (_: Exception) {}
        closeSocket()
        io.shutdown()
    }

    // ================= BLUETOOTH =================
    private fun btAdapter(): BluetoothAdapter? {
        val m = getSystemService(BluetoothManager::class.java)
        return m?.adapter
    }

    private fun hasBtPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun nameOf(d: BluetoothDevice): String {
        return try { d.name ?: d.address } catch (_: Exception) { "Device" }
    }

    @SuppressLint("MissingPermission")
    private fun loadDevices() {
        try {
            val list = if (hasBtPermission()) (btAdapter()?.bondedDevices?.toList() ?: emptyList()) else emptyList()
            devices = list.sortedBy { if (nameOf(it) == "SmartCane") 0 else 1 }
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, devices.map { nameOf(it) })
        } catch (_: Exception) {}
    }

    private fun startConnect() {
        if (!hasBtPermission()) {
            setStatus(St.ERROR, "Kailangan ng Bluetooth permission")
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 2)
            return
        }
        val ad = btAdapter()
        if (ad == null) { setStatus(St.ERROR, "Walang Bluetooth ang phone"); return }
        if (!ad.isEnabled) { setStatus(St.ERROR, "I-ON muna ang Bluetooth ng phone"); return }
        loadDevices()
        val d = devices.getOrNull(spinner.selectedItemPosition)
        if (d == null) { setStatus(St.ERROR, "I-pair muna ang SmartCane sa Bluetooth settings"); return }
        wantConnected = true
        retryCount = 0
        connectTo(d)
    }

    private fun connectTo(d: BluetoothDevice) {
        if (!connecting.compareAndSet(false, true)) return
        currentAddress = d.address
        setStatus(St.CONNECTING, "Kumokonekta sa ${nameOf(d)}...")
        setBusy(true)
        thread {
            var s: BluetoothSocket? = null
            try {
                closeSocket()
                s = d.createRfcommSocketToServiceRecord(spp)
                s.connect()
                socket = s
                connected = true
                retryCount = 0
                connecting.set(false)
                setBusy(false)
                setStatus(St.CONNECTED, "Nakakonekta: ${nameOf(d)}")
                addEntry("sys", "Nakakonekta: ${nameOf(d)}", "KONEKSYON")
                send("APP_CONNECTED")
            } catch (e: Exception) {
                try { s?.close() } catch (_: Exception) {}
                onLost(false, e.message ?: "walang detalye")
                return@thread
            }
            try {
                val reader = s.inputStream.bufferedReader()
                while (true) {
                    val line = reader.readLine() ?: break
                    try { handle(line.trim()) } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
            if (socket === s) onLost(true, "naputol ang koneksyon")
        }
    }

    private fun onLost(dropped: Boolean, reason: String) {
        connected = false
        closeSocket()
        connecting.set(false)
        setBusy(false)
        ui.post {
            addEntry("sys", "Nadiskonekta ($reason)", "KONEKSYON")
            if (dropped) speakText("Nadiskonekta ang Smart Cane", "local")
            if (wantConnected && retryCount < maxRetry) {
                retryCount++
                setStatus(St.CONNECTING, "Susubukan ulit ($retryCount/$maxRetry)...")
                ui.postDelayed({
                    if (wantConnected && !connected) {
                        val d = devices.firstOrNull { it.address == currentAddress }
                        if (d != null) connectTo(d) else setStatus(St.OFF, "Nadiskonekta")
                    }
                }, 5000)
            } else {
                wantConnected = false
                setStatus(St.OFF, "Nadiskonekta. Pindutin ang Kumonekta.")
            }
        }
    }

    private fun disconnectByUser() {
        wantConnected = false
        connected = false
        closeSocket()
        connecting.set(false)
        setBusy(false)
        setStatus(St.OFF, "Nadiskonekta")
        addEntry("sys", "Idiniskonekta mo ang device", "KONEKSYON")
    }

    private fun closeSocket() {
        val s = socket
        socket = null
        try { s?.close() } catch (_: Exception) {}
    }

    private fun send(text: String) {
        try {
            io.execute {
                try {
                    val o = socket?.outputStream
                    if (o != null) {
                        o.write((text + "\n").toByteArray())
                        o.flush()
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    // ================= MENSAHE MULA SA TUNGKOD =================
    // "AUDIO:0008", "SOS", "SOS:lat,lng"
    private fun handle(l: String) {
        if (l.isEmpty()) return
        if (l.startsWith("AUDIO:")) {
            val n = l.substring(6).trim().toIntOrNull()
            if (n == null) {
                send("AUDIO_DONE")
                addEntry("sys", "Hindi maintindihan: ${l.take(40)}", "ERROR")
                return
            }
            addEntry("audio", phrase(n), String.format(Locale.US, "AUDIO:%04d", n))
            ui.post { speak(n) }
        } else if (l.startsWith("SOS")) {
            coords = l.substringAfter(':', "").ifBlank { null }
            isTest = false
            smsRetries = 0
            addEntry("sos", "SOS mula sa tungkod", "SOS")
            ui.post { sendSms() }
        } else {
            addEntry("sys", l.take(60), "DEVICE")
        }
    }

    // ================= AUDIO =================
    private fun initTts() {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(this) { st ->
            val e = engine
            if (st == TextToSpeech.SUCCESS && e != null) {
                val r = e.setLanguage(Locale.forLanguageTag("fil-PH"))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    e.setLanguage(Locale.getDefault())
                }
                e.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { if (id == "dev") send("AUDIO_DONE") }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { if (id == "dev") send("AUDIO_DONE") }
                    override fun onStop(id: String?, interrupted: Boolean) { if (id == "dev") send("AUDIO_DONE") }
                })
                ttsReady = true
            }
        }
        tts = engine
    }

    // Kung may res/raw/a0008.mp3, iyon ang tutugtog. Kung wala, boses ng phone (TTS).
    private fun speak(n: Int) {
        val res = resources.getIdentifier(String.format(Locale.US, "a%04d", n), "raw", packageName)
        if (res != 0) playRaw(res) else speakText(phrase(n), "dev")
    }

    private fun speakText(text: String, id: String) {
        val t = tts
        if (t == null || !ttsReady) {
            if (id == "dev") send("AUDIO_DONE")
            return
        }
        val r = t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (r != TextToSpeech.SUCCESS && id == "dev") send("AUDIO_DONE")
    }

    private fun playRaw(res: Int) {
        try {
            mp?.release()
            val p = MediaPlayer.create(this, res)
            if (p == null) { send("AUDIO_DONE"); return }
            p.setOnCompletionListener { send("AUDIO_DONE") }
            p.setOnErrorListener { _, _, _ -> send("AUDIO_DONE"); true }
            mp = p
            p.start()
        } catch (_: Exception) {
            send("AUDIO_DONE")
        }
    }

    private fun phrase(n: Int): String {
        for (g in table.indices) {
            for (z in 0..2) {
                if (table[g][z] == n) return "Sagabal sa ${where[g]}, ${dist[z]} sentimetro"
            }
        }
        return when (n) {
            24 -> "Paakyat na daan"
            25 -> "Pababang daan"
            28 -> "Patag na daan"
            29 -> "Hagdan pababa"
            30 -> "Hagdan pataas"
            31 -> "May butas"
            40 -> "Naipadala ang S O S"
            41 -> "Hindi naipadala ang S O S"
            42 -> "Nakakonekta ang Smart Cane"
            else -> "Alerto $n"
        }
    }

    // ================= SMS =================
    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager? {
        return if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else SmsManager.getDefault()
    }

    private fun saveContact() {
        prefs.edit().putString("num", contact.text.toString().trim()).apply()
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(contact.windowToken, 0)
        } catch (_: Exception) {}
        addEntry("sys", "Na-save ang contact", "CONTACT")
        speakText("Na-save ang contact", "local")
    }

    private fun smsFailed(reason: String) {
        send("SMS_FAIL")
        addEntry("sms", "SMS hindi naipadala: $reason", "SMS_FAIL")
    }

    private fun sendSms() {
        val num = prefs.getString("num", "").orEmpty().trim()
        if (num.count { it.isDigit() } < 7) { smsFailed("walang tamang contact"); return }
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            smsFailed("walang SMS permission")
            requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 3)
            return
        }
        val loc = coords?.let { " https://maps.google.com/?q=$it" }.orEmpty()
        val msg = (if (isTest) "[TEST] " else "") + "SOS! Kailangan ng tulong." + loc
        try {
            val sm = smsManager()
            if (sm == null) { smsFailed("walang SMS service"); return }
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(sentAction).setPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            sm.sendTextMessage(num, null, msg, pi, null)
        } catch (e: Exception) {
            smsFailed(e.message ?: "error")
        }
    }
}
