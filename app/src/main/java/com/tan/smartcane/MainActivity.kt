package com.tan.smartcane

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaPlayer
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.SmsManager
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val sentAction = "com.tan.smartcane.SMS_SENT"
    private val ui = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor()   // isang pila: hindi nagkakabuhol ang pagkakasunod ng padala
    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var contact: EditText
    private lateinit var spinner: Spinner
    private var devices = listOf<BluetoothDevice>()
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var target: BluetoothDevice? = null   // device na kokonektahan
    @Volatile private var running = false                   // tumatakbo ang connect/reconnect loop
    @Volatile private var lastSosMs = 0L
    private var lastTestMs = 0L              // huling pindot ng Test SMS button (iwas dobleng padala)
    private var coords: String? = null
    private var retries = 0
    private var isTest = false
    private var locMsg: String? = null       // location SMS na ipapadala pagkatapos ma-send ang SOS
    private var retryRun: Runnable? = null   // nakaabang na retry ng SOS
    private var locWaitId = 0                // pang-cancel ng paghihintay sa location

    // ---------- LOCATION NG PHONE ----------
    private var locStarted = false
    private var lastLoc: Location? = null
    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // Huwag palitan ang tumpak na GPS fix ng malabong network fix
            val o = lastLoc
            if (o == null || location.provider == LocationManager.GPS_PROVIDER ||
                location.accuracy <= o.accuracy || System.currentTimeMillis() - o.time > 60_000) lastLoc = location
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    // ---------- AUDIO ----------
    // false = DFPlayer sa Arduino ang tutugtog ng audio (phone hindi mag-p-play).
    // true  = phone ang tutugtog (TTS o res/raw).
    private val phoneAudio = true
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var player: MediaPlayer? = null

    // Pareho ng OBSTACLE_AUDIO table sa Arduino: [grupo][20cm, 50cm, 80cm]
    private val obstacleAudio = arrayOf(
        intArrayOf(15, 8, 1), intArrayOf(16, 9, 2), intArrayOf(17, 10, 3), intArrayOf(18, 11, 4),
        intArrayOf(19, 12, 5), intArrayOf(20, 13, 6), intArrayOf(21, 14, 7)
    )
    private val groupText = listOf("left", "front", "right", "left and front", "right and front", "left and right", "all sides")
    private val zoneText = listOf("very close", "close", "ahead")

    // Number ng audio -> sasabihin ng phone kung walang res/raw/aXXXX.mp3
    private val phrases: Map<Int, String> = buildMap {
        for (g in 0..6) for (z in 0..2) put(obstacleAudio[g][z], "Obstacle on the ${groupText[g]}, ${zoneText[z]}")
        put(24, "Going uphill")
        put(25, "Going downhill")
        put(28, "Flat ground")
        put(31, "Hole ahead")
        put(40, "Emergency message sent")
        put(41, "Message failed. Trying again")
        put(42, "Smart Cane connected")
        put(43, "Low battery")
    }

    // getSystemService(SmsManager) ay gumagana lang sa Android 12 pataas
    private fun sms(): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()

    // Send successfully? Yes -> audio 0040 (SMS_OK). No -> audio 0041 (SMS_FAIL), 1.5 sec, retry.
    private val smsResult = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (resultCode == RESULT_OK) {
                retries = 0; send("SMS_OK"); note("SMS sent successfully"); sendLocation()
                if (isTest) toast("Test SMS sent")
            } else {
                send("SMS_FAIL"); note("SMS failed")
                if (isTest) toast("Test SMS failed")
                if (!isTest && retries++ < 10) scheduleRetry()
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // para hindi mag-sleep ang app habang gamit
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; textSize = 20f; setOnClickListener { f() } }
        fun label(t: String) = TextView(this).apply { text = t; textSize = 16f }
        // TalkBack: babasahin nang kusa kapag nagbago ang status (tingnan ang say())
        status = TextView(this).apply {
            text = "Not connected"; textSize = 22f
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        spinner = Spinner(this).apply { id = View.generateViewId() }
        contact = EditText(this).apply {
            id = View.generateViewId()
            textSize = 20f
            inputType = InputType.TYPE_CLASS_PHONE; setText(prefs.getString("num", ""))
        }
        val lblDevice = label("Bluetooth device").apply { labelFor = spinner.id }
        val lblContact = label("Emergency contact number").apply { labelFor = contact.id }
        log = TextView(this).apply {
            textSize = 14f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO   // debug log: laktawan ng TalkBack
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40)
            addView(status); addView(lblDevice); addView(spinner)
            addView(btn("Connect to Smart Cane") { connectSelected() })
            addView(lblContact); addView(contact)
            addView(btn("Save Contact") {
                val n = contact.text.toString().trim()
                prefs.edit().putString("num", n).apply()
                note("Saved Contact")
                Toast.makeText(this@MainActivity, if (n.isBlank()) "Contact cleared" else "Contact saved", Toast.LENGTH_SHORT).show()
            })
            addView(btn("Send Test SMS") { testSms() })
            addView(log)
        }
        setContentView(ScrollView(this).apply { addView(root) })

        val f = IntentFilter(sentAction)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(smsResult, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(smsResult, f)

        val p = mutableListOf(Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= 31) p += Manifest.permission.BLUETOOTH_CONNECT
        p += Manifest.permission.ACCESS_FINE_LOCATION
        p += Manifest.permission.ACCESS_COARSE_LOCATION
        requestPermissions(p.toTypedArray(), 1)
        loadDevices()
        startLocation()

        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { audioDone() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { audioDone() }
                })
                ttsReady = true
            } else note("TTS not working (install a Text-to-speech engine)")
        }
    }

    override fun onResume() { super.onResume(); startLocation() }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        loadDevices()
        startLocation()
        autoConnect()
    }

    // Simulan ang location updates ng phone (GPS + network). Ligtas tawagin nang paulit-ulit.
    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (locStarted) return
        val lm = getSystemService(LocationManager::class.java) ?: return
        for (prov in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (lm.allProviders.contains(prov)) {
                    lm.requestLocationUpdates(prov, 5000L, 0f, locListener, Looper.getMainLooper())
                    locStarted = true
                }
            } catch (_: Exception) {}   // walang permission pa, o walang ganitong provider
        }
    }

    // Pinakabagong kilalang location mula sa GPS / network / passive. null kung wala.
    @SuppressLint("MissingPermission")
    private fun bestLocation(): Location? {
        val lm = getSystemService(LocationManager::class.java) ?: return null
        var best: Location? = null
        for (prov in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            try {
                val l = lm.getLastKnownLocation(prov) ?: continue
                val b = best
                if (b == null || l.time > b.time) best = l
            } catch (_: Exception) {}
        }
        return best
    }

    // Pinakabago sa pagitan ng galing sa listener at ng last known ng system
    private fun currentLocation(): Location? {
        val a = lastLoc
        val b = bestLocation()
        return if (a == null) b else if (b == null) a else if (b.time > a.time) b else a
    }

    // Google Maps link na pipindutin sa SMS (kusang nagiging clickable)
    private fun mapsLink(latLng: String) = "https://maps.google.com/?q=$latLng"

    private fun locationText(l: Location): String {
        val ageMin = (System.currentTimeMillis() - l.time) / 60000
        return "Location: " + mapsLink("%.6f,%.6f".format(Locale.US, l.latitude, l.longitude)) +
            (if (ageMin >= 2) " (last known, $ageMin min ago)" else "")
    }

    // Coordinates galing ESP32 ("lat,lng"). null kapag sira ang format o 0,0 (walang GPS fix)
    private fun parseCoords(s: String): String? {
        val p = s.split(',')
        if (p.size != 2) return null
        val la = p[0].trim().toDoubleOrNull() ?: return null
        val lo = p[1].trim().toDoubleOrNull() ?: return null
        if (la !in -90.0..90.0 || lo !in -180.0..180.0 || (la == 0.0 && lo == 0.0)) return null
        return "%.6f,%.6f".format(Locale.US, la, lo)
    }

    @SuppressLint("MissingPermission")
    private fun loadDevices() {
        try {
            val ad = getSystemService(BluetoothManager::class.java).adapter
            devices = ad?.bondedDevices?.toList() ?: emptyList()
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                devices.map { it.name ?: it.address })
        } catch (_: SecurityException) {}
    }

    // ---------- BLUETOOTH: connect + auto-reconnect ----------
    private fun hasBtPerm() = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun connectSelected() {
        val d = devices.getOrNull(spinner.selectedItemPosition)
            ?: return note("No device (pair in Settings)")
        connectTo(d)
    }

    // Kokonekta sa huling ginamit na device pagbukas ng app
    private fun autoConnect() {
        val a = prefs.getString("dev", null) ?: return
        val i = devices.indexOfFirst { it.address == a }
        if (i < 0) return
        spinner.setSelection(i)
        connectTo(devices[i])
    }

    @SuppressLint("MissingPermission")
    private fun connectTo(d: BluetoothDevice) {
        if (!hasBtPerm()) return note("Allow the Bluetooth permission first")
        prefs.edit().putString("dev", d.address).apply()
        val changed = target?.address != d.address
        target = d
        if (running) {                       // may tumatakbo na: huwag gumawa ng pangalawang thread
            if (changed) { try { socket?.close() } catch (_: Exception) {} }
            return
        }
        running = true
        thread {
            while (running) {
                val dev = target ?: break
                try {
                    try { getSystemService(BluetoothManager::class.java).adapter?.cancelDiscovery() } catch (_: Exception) {}
                    val s = dev.createRfcommSocketToServiceRecord(spp)
                    socket = s
                    s.connect()
                    say("Connected: ${dev.name}", announce = false)   // audio 0042 ang nagsasabi na nito, iwas dobleng boses
                    send("APP_CONNECTED")
                    val r = s.inputStream.bufferedReader()
                    while (true) handle((r.readLine() ?: break).trim())
                } catch (e: Exception) { note("Error: ${e.message}") }
                try { socket?.close() } catch (_: Exception) {}
                if (running) { say("Disconnected, Try again..."); Thread.sleep(3000) }
            }
            running = false
        }
    }

    // ESP32/Arduino -> app: "AUDIO:0016", "SOS" o "SOS:lat,lng", "HELLO"
    private fun handle(l: String) {
        if (l.isEmpty()) return
        note("ESP32: $l")
        if (l.startsWith("AUDIO:")) {
            val n = l.substringAfter(':').trim().toIntOrNull()
            if (n != null && phoneAudio) ui.post { playAudio(n) }
        } else if (l.startsWith("SOS")) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSosMs < 3000) return          // iwas dobleng SMS sa isang pindot
            lastSosMs = now
            val c = parseCoords(l.substringAfter(':', ""))
            ui.post {
                coords = c; isTest = false; retries = 0
                cancelRetry(); locWaitId++              // kanselahin ang lumang retry/paghihintay ng location
                sendSms()
            }
        } else if (l == "HELLO") {
            send("APP_CONNECTED")            // nag-reset ang Arduino: i-initialize ulit
        }
    }

    // ---------- TEST SMS ----------
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // Test button: pareho ng totoong SOS pero may "[TEST] " tag, walang retry, at gumagamit ng location ng phone.
    private fun testSms() {
        val now = SystemClock.elapsedRealtime()
        // Huwag gambalain ang totoong SOS (retry / paghihintay ng location, hanggang ~3 minuto)
        if (lastSosMs != 0L && now - lastSosMs < 180_000) return toast("Real SOS in progress. Try again later")
        if (now - lastTestMs < 10_000) return toast("Please wait a few seconds")
        val saved = prefs.getString("num", "").orEmpty()
        if (saved.isBlank()) return toast("Save the emergency contact first")
        if (contact.text.toString().trim() != saved) return toast("Press Save Contact first")
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 1)
            return toast("Allow the SMS permission, then try again")
        }
        lastTestMs = now
        coords = null; isTest = true; retries = 0   // coords = null: huwag gamitin ang lumang coords ng ESP32
        cancelRetry(); locWaitId++
        toast("Sending test SMS")
        sendSms()
    }

    // ---------- SMS ----------
    private fun scheduleRetry() {
        cancelRetry()
        val r = Runnable { sendSms() }
        retryRun = r
        ui.postDelayed(r, 1500)
    }

    private fun cancelRetry() { retryRun?.let { ui.removeCallbacks(it) }; retryRun = null }

    private fun sendSms() {
        val num = prefs.getString("num", "").orEmpty()
        if (num.isBlank() || checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            send("SMS_FAIL"); note("No contact or SMS permission"); return
        }
        // Location: kung may galing sa ESP32 (SOS:lat,lng) gamitin iyon, kung wala, gamitin ang location ng phone.
        val tag = if (isTest) "[TEST] " else ""
        val phone = if (coords == null) currentLocation() else null
        locMsg = when {
            coords != null -> tag + "Location: " + mapsLink("$coords")
            phone != null  -> tag + locationText(phone)
            else           -> null
        }
        note(locMsg ?: "No location yet (will keep trying after SOS is sent)")
        val msg = tag + "SOS! I need help."
        val pi = PendingIntent.getBroadcast(this, 0, Intent(sentAction).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            sms().sendTextMessage(num, null, msg, pi, null)
        } catch (e: Exception) { send("SMS_FAIL"); note("Error: ${e.message}") }
    }

    // Hiwalay na SMS ang location, pagkatapos lang ma-send ang SOS. Hindi nito naaapektuhan ang SOS.
    // Kung wala pang location, maghihintay ng hanggang 60 segundo.
    private fun sendLocation() {
        locWaitId++
        val m = locMsg
        locMsg = null
        if (m != null) smsPlain(m)
        else waitForLocation(locWaitId, 12, if (isTest) "[TEST] " else "")
    }

    private fun waitForLocation(id: Int, tries: Int, tag: String) {
        if (id != locWaitId) return                 // may bagong SOS na
        val l = currentLocation()
        if (l != null) { smsPlain(tag + locationText(l)); return }
        if (tries <= 0) { note("No location found"); return }
        ui.postDelayed({ waitForLocation(id, tries - 1, tag) }, 5000)
    }

    private fun smsPlain(m: String) {
        val num = prefs.getString("num", "").orEmpty()
        if (num.isBlank()) return
        try {
            sms().sendTextMessage(num, null, m, null, null)
            note("Location SMS sent successfully")
        } catch (e: Exception) { note("Location SMS error: ${e.message}") }
    }

    // ---------- AUDIO ----------
    // Arduino -> "AUDIO:0016". Una subukan ang res/raw/a0016.mp3, kung wala, gagamitin ang TTS.
    private fun playAudio(n: Int) {
        // Itigil ang kasalukuyang tunog para hindi magpatong (hindi ito nagti-trigger ng AUDIO_DONE)
        tts?.stop()
        player?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        player = null

        val resId = resources.getIdentifier("a%04d".format(n), "raw", packageName)
        if (resId != 0) {
            val mp = MediaPlayer.create(this, resId)
            if (mp != null) {
                player = mp
                mp.setOnCompletionListener { it.release(); if (player === it) player = null; audioDone() }
                mp.setOnErrorListener { m, _, _ -> m.release(); if (player === m) player = null; audioDone(); true }
                mp.start()
                return
            }
        }
        val text = phrases[n]
        if (text != null && ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "a$n")
        } else {
            note("No audio/TTS for $n")
            audioDone()
        }
    }

    // Sabihin sa Arduino na tapos na ang audio para makapag-play ito ng susunod.
    private fun audioDone() { send("AUDIO_DONE") }

    private fun send(s: String) {
        sender.execute {
            try { socket?.outputStream?.apply { write((s + "\n").toByteArray()); flush() } } catch (_: Exception) {}
        }
    }

    // announce = false: hindi babasahin ng TalkBack ang pagbabago. Walang update kung pareho ang text.
    private fun say(s: String, announce: Boolean = true) {
        ui.post {
            if (status.text.toString() == s) return@post
            status.accessibilityLiveRegion =
                if (announce) View.ACCESSIBILITY_LIVE_REGION_POLITE else View.ACCESSIBILITY_LIVE_REGION_NONE
            status.text = s
        }
    }
    private fun note(s: String) { ui.post { log.text = ("$s\n${log.text}").take(3000) } }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        ui.removeCallbacksAndMessages(null)
        try { unregisterReceiver(smsResult) } catch (_: Exception) {}
        tts?.shutdown(); player?.release()
        try { getSystemService(LocationManager::class.java)?.removeUpdates(locListener) } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        sender.shutdown()
    }
}
