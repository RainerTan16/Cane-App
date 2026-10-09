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
import android.view.accessibility.AccessibilityManager
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
    private lateinit var rootView: View
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var spinner: Spinner
    private lateinit var btnConnect: Button
    private lateinit var btnVoice: Button
    private lateinit var contact: EditText
    private lateinit var latestCard: LinearLayout
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
    private var voiceGuide = true

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
            val test = i.getBooleanExtra("test", false)
            smsDone(resultCode == Activity.RESULT_OK, test, "", true)
        }
    }

    // ================= ACCESSIBILITY =================
    private fun touchExploration(): Boolean {
        val am = getSystemService(AccessibilityManager::class.java)
        return am != null && am.isTouchExplorationEnabled
    }

    // Isang paraan lang ng pagsasalita para sa mga mensahe ng app:
    // - kung naka-ON ang TalkBack: dadaan sa TalkBack (walang dobleng boses)
    // - kung hindi: boses ng app, kung naka-ON ang "Gabay na boses"
    private fun announce(text: String) {
        ui.post {
            if (touchExploration()) rootView.announceForAccessibility(text)
            else if (voiceGuide) speakLocal(text)
        }
    }

    // Sinasabi ang pangalan ng button kapag pinindot, kung walang TalkBack.
    private fun guide(label: String) {
        if (!touchExploration() && voiceGuide) speakLocal(label)
    }

    private fun voiceLabel(): String =
        if (voiceGuide) "Gabay na boses: naka-ON" else "Gabay na boses: naka-OFF"

    private fun toggleVoice() {
        voiceGuide = !voiceGuide
        prefs.edit().putBoolean("voice", voiceGuide).apply()
        val msg = voiceLabel()
        btnVoice.text = msg
        btnVoice.contentDescription = msg
        if (touchExploration()) rootView.announceForAccessibility(msg)
        else speakLocal(msg)
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

    private fun button(label: String, primary: Boolean, speakLabel: Boolean = true, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.contentDescription = label
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
        b.setOnClickListener {
            if (speakLabel) guide(label)
            onClick()
        }
        return b
    }

    private fun hideFromReader(v: View) {
        v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun buildUi() {
        // header na may logo
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setBackgroundColor(navy)
        header.setPadding(dp(16), dp(10), dp(16), dp(10))
        val logo = ImageView(this)
        val logoRes = resources.getIdentifier("ic_launcher_foreground", "drawable", packageName)
        if (logoRes != 0) logo.setImageResource(logoRes)
        hideFromReader(logo)
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
        hideFromReader(statusDot)
        val dotLp = LinearLayout.LayoutParams(dp(14), dp(14))
        dotLp.rightMargin = dp(10)
        row.addView(statusDot, dotLp)
        statusText = TextView(this)
        statusText.text = "Hindi nakakonekta"
        statusText.textSize = 20f
        statusText.setTextColor(navy)
        statusText.typeface = Typeface.DEFAULT_BOLD
        row.addView(statusText)
        conn.addView(row)
        val devCaption = caption("Pumili ng device")
        conn.addView(devCaption)
        spinner = Spinner(this)
        spinner.id = View.generateViewId()
        devCaption.labelFor = spinner.id
        conn.addView(spinner, LinearLayout.LayoutParams(match, dp(52)))
        btnConnect = button("Kumonekta sa Smart Cane", true) { startConnect() }
        conn.addView(btnConnect)
        conn.addView(button("Idiskonekta", false) { disconnectByUser() })
        content.addView(conn)

        // --- Huling alerto (iisang grupo para sa TalkBack) ---
        val latest = LinearLayout(this)
        latestCard = latest
        latest.orientation = LinearLayout.VERTICAL
        latest.background = rounded(navy, 0, 18)
        latest.setPadding(dp(18), dp(16), dp(18), dp(16))
        val latestLp = LinearLayout.LayoutParams(match, wrap)
        latestLp.topMargin = dp(12)
        latest.layoutParams = latestLp
        latest.isFocusable = true
        latest.contentDescription = "Huling alerto: wala pang alerto"
        val lbl = TextView(this)
        lbl.text = "HULING ALERTO"
        lbl.textSize = 12f
        lbl.setTextColor(Color.parseColor("#8EE0D4"))
        lbl.typeface = Typeface.DEFAULT_BOLD
        lbl.letterSpacing = 0.1f
        hideFromReader(lbl)
        latest.addView(lbl)
        latestTitle = TextView(this)
        latestTitle.text = "Wala pang alerto"
        latestTitle.textSize = 26f
        latestTitle.setTextColor(Color.WHITE)
        latestTitle.typeface = Typeface.DEFAULT_BOLD
        latestTitle.setPadding(0, dp(6), 0, dp(6))
        hideFromReader(latestTitle)
        latest.addView(latestTitle)
        latestMeta = TextView(this)
        latestMeta.text = "Hintayin ang alerto mula sa tungkod"
        latestMeta.textSize = 14f
        latestMeta.setTextColor(Color.parseColor("#A8B5C8"))
        latestMeta.typeface = Typeface.MONOSPACE
        hideFromReader(latestMeta)
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
        val contactCaption = caption("Numero ng emergency contact")
        cc.addView(contactCaption)
        contact = EditText(this)
        contact.id = View.generateViewId()
        contactCaption.labelFor = contact.id
        contact.hint = "Halimbawa: 09171234567"
        contact.textSize = 20f
        contact.inputType = InputType.TYPE_CLASS_PHONE
        contact.setSingleLine(true)
        contact.minHeight = dp(56)
        contact.setText(prefs.getString("num", ""))
        cc.addView(contact, LinearLayout.LayoutParams(match, wrap))
        cc.addView(button("I-save ang contact", true) { saveContact() })
        cc.addView(button("Test SMS", false) { sendSms(true) })
        content.addView(cc)

        // --- Tunog at pagsubok ---
        val tc = card()
        tc.addView(sectionTitle("TUNOG AT PAGSUBOK"))
        btnVoice = button(voiceLabel(), false, false) { toggleVoice() }
        tc.addView(btnVoice)
        tc.addView(button("Test alert sa device", false) { testDevice() })
        tc.addView(button("Test suara", false) {
            speakLocal("Pagsubok ng boses. Kung naririnig mo ito, gumagana ang tunog.")
        })
        content.addView(tc)

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.addView(content)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(bg)
        root.addView(header, LinearLayout.LayoutParams(match, wrap))
        root.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))
        rootView = root
        setContentView(root)
    }

    // say = True: sasabihin ang mensahe (TalkBack o boses ng app). Hindi na gumagamit ng live region.
    private fun setStatus(st: St, text: String, say: Boolean) {
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
        if (say) announce(text)
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
                latestCard.contentDescription = "Huling alerto: $title, $time"
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
        hideFromReader(stripe)
        val stripeLp = LinearLayout.LayoutParams(dp(5), dp(36))
        stripeLp.rightMargin = dp(10)
        row.addView(stripe, stripeLp)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val t1 = TextView(this)
        t1.text = title
        t1.textSize = 16f
        t1.setTextColor(navy)
        hideFromReader(t1)
        col.addView(t1)
        val t2 = TextView(this)
        t2.text = code
        t2.textSize = 12f
        t2.setTextColor(color)
        t2.typeface = Typeface.MONOSPACE
        hideFromReader(t2)
        col.addView(t2)
        row.addView(col, LinearLayout.LayoutParams(0, wrap, 1f))
        val t3 = TextView(this)
        t3.text = time
        t3.textSize = 12f
        t3.setTextColor(gray)
        hideFromReader(t3)
        row.addView(t3)
        return row
    }

    // ================= LIFECYCLE =================
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // pansamantala hangga't walang foreground service
        window.statusBarColor = navy
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        voiceGuide = prefs.getBoolean("voice", true)
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
            setStatus(St.ERROR, "Kailangan ng Bluetooth permission", true)
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 2)
            return
        }
        val ad = btAdapter()
        if (ad == null) { setStatus(St.ERROR, "Walang Bluetooth ang phone", true); return }
        if (!ad.isEnabled) { setStatus(St.ERROR, "I-ON muna ang Bluetooth ng phone", true); return }
        loadDevices()
        val d = devices.getOrNull(spinner.selectedItemPosition)
        if (d == null) { setStatus(St.ERROR, "I-pair muna ang SmartCane sa Bluetooth settings", true); return }
        wantConnected = true
        retryCount = 0
        connectTo(d, true)
    }

    private fun connectTo(d: BluetoothDevice, loud: Boolean) {
        if (!connecting.compareAndSet(false, true)) return
        currentAddress = d.address
        setStatus(St.CONNECTING, "Kumokonekta sa ${nameOf(d)}...", loud)
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
                setStatus(St.CONNECTED, "Nakakonekta: ${nameOf(d)}", true)
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
            if (wantConnected && retryCount < maxRetry) {
                retryCount++
                setStatus(St.CONNECTING, "Susubukan ulit ($retryCount/$maxRetry)...", false)
                if (dropped) announce("Nadiskonekta ang Smart Cane. Susubukan ulit.")
                ui.postDelayed({
                    if (wantConnected && !connected) {
                        val d = devices.firstOrNull { it.address == currentAddress }
                        if (d != null) connectTo(d, false) else setStatus(St.OFF, "Nadiskonekta", true)
                    }
                }, 5000)
            } else {
                wantConnected = false
                setStatus(St.OFF, "Nadiskonekta. Pindutin ang Kumonekta.", true)
            }
        }
    }

    private fun disconnectByUser() {
        wantConnected = false
        connected = false
        closeSocket()
        connecting.set(false)
        setBusy(false)
        setStatus(St.OFF, "Nadiskonekta", true)
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

    private fun testDevice() {
        if (!connected) {
            announce("Hindi pa nakakonekta sa Smart Cane")
            addEntry("sys", "Hindi pa nakakonekta", "TEST")
            return
        }
        send("TEST")
        addEntry("sys", "Test alert ipinadala", "TEST")
        announce("Ipinadala ang test alert")
    }

    // ================= MENSAHE MULA SA TUNGKOD =================
    // "AUDIO:0008", "SOS", "SOS:lat,lng"
    private fun handle(l: String) {
        if (l.isEmpty()) return
        if (l.startsWith("AUDIO:")) {
            val n = l.substring(6).trim().toIntOrNull()
            if (n == null) {
                addEntry("sys", "Hindi maintindihan: ${l.take(40)}", "ERROR")
                return
            }
            addEntry("audio", phrase(n), String.format(Locale.US, "AUDIO:%04d", n))
            ui.post { speak(n) }
        } else if (l.startsWith("SOS")) {
            coords = l.substringAfter(':', "").ifBlank { null }
            smsRetries = 0
            addEntry("sos", "SOS mula sa tungkod", "SOS")
            ui.post { sendSms(false) }
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
                    override fun onDone(id: String?) { audioDone(id) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { audioDone(id) }
                    override fun onStop(id: String?, interrupted: Boolean) { audioDone(id) }
                })
                ttsReady = true
            }
        }
        tts = engine
    }

    // "dev:31" = alerto mula sa tungkod. Sasabihin sa Arduino na tapos na ang audio 31 (AUDIO_DONE:31).
    // Maliit na pahinga bago magsabi, para hindi maputol ang dulo sa AirPods.
    private fun audioDone(id: String?) {
        if (id != null && id.startsWith("dev:")) {
            val n = id.substring(4)
            ui.postDelayed({ send("AUDIO_DONE:$n") }, 150)
        }
    }

    // Kung may res/raw/a0008.mp3, iyon ang tutugtog. Kung wala, boses ng phone (TTS).
    private fun speak(n: Int) {
        val res = resources.getIdentifier(String.format(Locale.US, "a%04d", n), "raw", packageName)
        if (res != 0) playRaw(res, n) else speakDevice(phrase(n), n)
    }

    // Alerto: QUEUE_ADD lang, hindi kailanman nagpuputol ng audio na tumutugtog.
    private fun speakDevice(text: String, n: Int) {
        val t = tts
        if (t == null || !ttsReady) {
            send("AUDIO_DONE:$n")
            return
        }
        val r = t.speak(text, TextToSpeech.QUEUE_ADD, null, "dev:$n")
        if (r != TextToSpeech.SUCCESS) send("AUDIO_DONE:$n")
    }

    // Mensahe ng app (button, status). Nakapila rin ito at hindi nagpuputol ng alerto.
    private fun speakLocal(text: String) {
        val t = tts
        if (t == null || !ttsReady) return
        t.speak(text, TextToSpeech.QUEUE_ADD, null, "local")
    }

    private fun playRaw(res: Int, n: Int) {
        try {
            mp?.release()
            val p = MediaPlayer.create(this, res)
            if (p == null) { send("AUDIO_DONE:$n"); return }
            p.setOnCompletionListener { audioDone("dev:$n") }
            p.setOnErrorListener { _, _, _ -> audioDone("dev:$n"); true }
            mp = p
            p.start()
        } catch (_: Exception) {
            send("AUDIO_DONE:$n")
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

    private fun validNumber(n: String): Boolean = n.count { it.isDigit() } >= 7

    private fun saveContact() {
        val num = contact.text.toString().trim()
        if (!validNumber(num)) {
            addEntry("sys", "Hindi tama ang numero", "CONTACT")
            announce("Hindi tama ang numero. Dapat hindi bababa sa pitong numero.")
            return
        }
        prefs.edit().putString("num", num).apply()
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(contact.windowToken, 0)
        } catch (_: Exception) {}
        addEntry("sys", "Na-save ang contact", "CONTACT")
        announce("Na-save ang contact")
    }

    // Ang test na SMS ay HINDI nagpapadala ng SMS_OK/SMS_FAIL sa tungkod, para hindi magsabi ng maling SOS.
    private fun smsDone(ok: Boolean, test: Boolean, reason: String, canRetry: Boolean) {
        if (ok) {
            smsRetries = 0
            if (test) {
                addEntry("sms", "Test SMS naipadala", "SMS_OK")
                announce("Naipadala ang test na SMS")
            } else {
                addEntry("sms", "SMS naipadala", "SMS_OK")
                send("SMS_OK")
            }
        } else {
            val why = if (reason.isEmpty()) "" else ": $reason"
            if (test) {
                addEntry("sms", "Test SMS pumalya$why", "SMS_FAIL")
                announce("Hindi naipadala ang test na SMS$why")
            } else {
                addEntry("sms", "SMS pumalya$why", "SMS_FAIL")
                send("SMS_FAIL")
                if (canRetry && smsRetries < 3) {
                    smsRetries++
                    ui.postDelayed({ sendSms(false) }, 1500)
                }
            }
        }
    }

    private fun sendSms(test: Boolean) {
        val num = prefs.getString("num", "").orEmpty().trim()
        if (!validNumber(num)) { smsDone(false, test, "walang naka-save na contact", false); return }
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            smsDone(false, test, "walang SMS permission", false)
            requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 3)
            return
        }
        val loc = if (test) "" else coords?.let { " https://maps.google.com/?q=$it" }.orEmpty()
        val msg = (if (test) "[TEST] Pagsubok lang ito." else "SOS! Kailangan ng tulong.") + loc
        try {
            val sm = smsManager()
            if (sm == null) { smsDone(false, test, "walang SMS service", false); return }
            val intent = Intent(sentAction).setPackage(packageName).putExtra("test", test)
            val pi = PendingIntent.getBroadcast(
                this, if (test) 1 else 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            sm.sendTextMessage(num, null, msg, pi, null)
        } catch (e: Exception) {
            smsDone(false, test, e.message ?: "error", false)
        }
    }
}
