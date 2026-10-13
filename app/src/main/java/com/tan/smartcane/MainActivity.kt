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
import android.os.*
import android.telephony.SmsManager
import android.text.InputType
import android.widget.*
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val sentAction = "com.tan.smartcane.SMS_SENT"
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var contact: EditText
    private lateinit var spinner: Spinner
    private var devices = listOf<BluetoothDevice>()
    private var socket: BluetoothSocket? = null
    private var coords: String? = null
    private var retries = 0
    private var isTest = false

    // Flowchart: Send successfully? Yes -> audio 0040 (SMS_OK). No -> audio 0041 (SMS_FAIL), delay 1-2 sec, retry.
    private val smsResult = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (resultCode == RESULT_OK) {
                retries = 0; send("SMS_OK"); note("SMS naipadala")
            } else {
                send("SMS_FAIL"); note("SMS pumalya")
                if (!isTest && retries++ < 3) ui.postDelayed({ sendSms() }, 1500)
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; textSize = 20f; setOnClickListener { f() } }
        status = TextView(this).apply { text = "Hindi nakakonekta"; textSize = 22f }
        contact = EditText(this).apply {
            hint = "Numero ng emergency contact"; textSize = 20f
            inputType = InputType.TYPE_CLASS_PHONE; setText(prefs.getString("num", ""))
        }
        spinner = Spinner(this)
        log = TextView(this).apply { textSize = 14f }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40)
            addView(status); addView(spinner)
            addView(btn("Connected Smart Cane") { connect() })
            addView(contact)
            addView(btn("Save Contact") {
                prefs.edit().putString("num", contact.text.toString().trim()).apply(); note("Saved Contact")
            })
            addView(btn("Test SMS") { isTest = true; sendSms() })
            addView(btn("Test alert sa device") { send("TEST") })
            addView(log)
        }
        setContentView(ScrollView(this).apply { addView(root) })

        val f = IntentFilter(sentAction)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(smsResult, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(smsResult, f)

        val p = mutableListOf(Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= 31) p += Manifest.permission.BLUETOOTH_CONNECT
        requestPermissions(p.toTypedArray(), 1)
        loadDevices()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) = loadDevices()

    @SuppressLint("MissingPermission")
    private fun loadDevices() {
        try {
            val ad = getSystemService(BluetoothManager::class.java).adapter
            devices = ad?.bondedDevices?.toList() ?: emptyList()
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                devices.map { it.name ?: it.address })
        } catch (_: SecurityException) {}
    }

    @SuppressLint("MissingPermission")
    private fun connect() {
        val d = devices.getOrNull(spinner.selectedItemPosition) ?: return note("Walang napiling device (i-pair muna sa Settings)")
        thread {
            try {
                socket?.close()
                val s = d.createRfcommSocketToServiceRecord(spp)
                s.connect(); socket = s
                say("Nakakonekta: ${d.name}")
                send("APP_CONNECTED")
                val r = s.inputStream.bufferedReader()
                while (true) handle((r.readLine() ?: break).trim())
            } catch (e: Exception) { note("Error: ${e.message}") }
            say("Nadiskonekta")
        }
    }

    // ESP32 -> app: "SOS" or "SOS:lat,lng"
    private fun handle(l: String) {
        note("ESP32: $l")
        if (l.startsWith("SOS")) {
            coords = l.substringAfter(':', "").ifBlank { null }
            isTest = false; retries = 0
            ui.post { sendSms() }
        }
    }

    private fun sendSms() {
        val num = prefs.getString("num", "").orEmpty()
        if (num.isBlank() || checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            send("SMS_FAIL"); note("Walang contact o SMS permission"); return
        }
        val loc = coords?.let { " https://maps.google.com/?q=$it" }.orEmpty()
        val msg = (if (isTest) "[TEST] " else "") + "SOS! I need help." + loc
        val pi = PendingIntent.getBroadcast(this, 0, Intent(sentAction).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            getSystemService(SmsManager::class.java).sendTextMessage(num, null, msg, pi, null)
        } catch (e: Exception) { send("SMS_FAIL"); note("Error: ${e.message}") }
    }

    private fun send(s: String) = thread {
        try { socket?.outputStream?.apply { write((s + "\n").toByteArray()); flush() } } catch (_: Exception) {}
    }

    private fun say(s: String) { ui.post { status.text = s } }
    private fun note(s: String) { ui.post { log.text = "$s\n${log.text}" } }

    override fun onDestroy() {
        super.onDestroy(); unregisterReceiver(smsResult); try { socket?.close() } catch (_: Exception) {}
    }
}
