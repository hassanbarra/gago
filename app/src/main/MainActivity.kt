package com.padbridge

import android.app.Activity
import android.content.Context
import android.hardware.BatteryState
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.text.InputType
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

private class Pad(val idx: Int) {
    val kb = FloatArray(17)  // buttons from key events
    val mb = FloatArray(17)  // triggers and d-pad from motion axes
    val a = FloatArray(4)    // lx, ly, rx, ry
    var last = ""
}

class MainActivity : Activity() {
    private val cBg = Color.parseColor("#13161c")
    private val cCard = Color.parseColor("#1d2129")
    private val cLine = Color.parseColor("#2a2f39")
    private val cMute = Color.parseColor("#8f98a6")
    private val cBlue = Color.parseColor("#2d7ff9")
    private val cRed = Color.parseColor("#e5484d")
    private val cGreen = Color.parseColor("#22b573")
    private val cAmber = Color.parseColor("#e0a100")

    private val client = OkHttpClient.Builder()
        .pingInterval(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var ws: WebSocket? = null
    @Volatile private var live = false
    private val pads = LinkedHashMap<Int, Pad>()
    private val ui = Handler(Looper.getMainLooper())
    private val names = arrayOf("✕", "○", "□", "△", "L1", "R1", "L2", "R2", "Share", "Options",
        "L3", "R3", "D-pad up", "D-pad down", "D-pad left", "D-pad right", "PS")

    private lateinit var status: TextView
    private lateinit var dot: View
    private lateinit var addr: EditText
    private lateinit var go: Button
    private lateinit var dim: View
    private lateinit var padText: TextView
    private lateinit var batteryText: TextView
    private lateinit var inputText: TextView

    // ----- small UI helpers -----

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int, oval: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        if (oval) setShape(GradientDrawable.OVAL) else cornerRadius = dp(radius).toFloat()
    }

    private fun lp(h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h).apply { topMargin = dp(top) }

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        setText(t)
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun button(t: String, color: Int) = Button(this).apply {
        setText(t)
        isAllCaps = false
        textSize = 17f
        setTextColor(Color.WHITE)
        background = rounded(color, 14)
        stateListAnimator = null
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(cCard, 16)
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun setStatus(t: String, color: Int) {
        status.text = t
        dot.background = rounded(color, 0, true)
    }

    private fun setRunning(on: Boolean) {
        go.text = if (on) "Stop" else "Start"
        go.background = rounded(if (on) cRed else cBlue, 14)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = getSharedPreferences("p", Context.MODE_PRIVATE)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(24))
        }
        col.addView(label("Pad Bridge", 28f, Color.WHITE, true))
        col.addView(label("Use your controller on your PC over USB", 14f, cMute), lp(top = 2))

        val card1 = card()
        card1.addView(label("PC address", 13f, cMute))
        addr = EditText(this).apply {
            setText(prefs.getString("addr", "localhost:8080"))
            setTextColor(Color.WHITE)
            textSize = 18f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            background = rounded(cBg, 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        card1.addView(addr, lp(top = 8))
        card1.addView(label("For USB, keep localhost:8080", 12f, cMute), lp(top = 8))
        col.addView(card1, lp(top = 20))

        go = button("Start", cBlue).apply {
            setOnClickListener {
                if (ws != null) disconnect() else {
                    prefs.edit().putString("addr", addr.text.toString().trim()).apply()
                    connect()
                }
            }
        }
        col.addView(go, lp(dp(56), 16))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dot = View(this)
        row.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(10) })
        status = label("Not connected", 15f, Color.WHITE)
        row.addView(status)
        col.addView(row, lp(top = 16))
        setStatus("Not connected", cMute)

        val card2 = card()
        padText = label("Looking for controller…", 15f, Color.WHITE, true)
        batteryText = label("", 18f, cGreen, true)
        inputText = label("Last input: none", 14f, cMute)
        card2.addView(padText)
        card2.addView(batteryText, lp(top = 6))
        card2.addView(inputText, lp(top = 6))
        col.addView(card2, lp(top = 16))

        col.addView(button("Dim screen", cLine).apply { setOnClickListener { setDim(true) } }, lp(dp(52), 12))

        col.addView(label(
            "1. Pair your controller in Android Bluetooth settings.\n" +
            "2. Plug the phone into the PC (USB debugging on) and start the PC program.\n" +
            "3. Tap Start. Keep this app open in front while you play.\n" +
            "Tap Dim screen to save battery. Tap the black screen to come back.",
            12f, cMute), lp(top = 20))

        dim = View(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            setOnClickListener { setDim(false) }
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(cBg)
            addView(col)
        }
        val root = FrameLayout(this)
        root.addView(scroll, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(dim, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setContentView(root)
    }

    // ----- controller list and battery (refreshed every 3 seconds) -----

    private fun isGamepadDevice(d: InputDevice) =
        (d.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
        (d.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    @Suppress("NewApi")
    private fun batteryOf(d: InputDevice): String {
        if (Build.VERSION.SDK_INT < 31) return "not shown on this Android version"
        val b = d.batteryState
        if (!b.isPresent) return "not reported by this controller"
        val c = b.capacity
        if (c.isNaN()) return "unknown"
        val pct = Math.round(c * 100)
        return if (b.status == BatteryState.STATUS_CHARGING) "$pct% (charging)" else "$pct%"
    }

    private fun refreshControllers() {
        val list = InputDevice.getDeviceIds().toList()
            .mapNotNull { InputDevice.getDevice(it) }
            .filter { isGamepadDevice(it) && !it.isVirtual }
        if (list.isEmpty()) {
            padText.text = "No controller found"
            batteryText.text = "Pair it in Bluetooth settings"
            batteryText.setTextColor(cMute)
            return
        }
        padText.text = if (list.size == 1) list[0].name else "${list.size} controllers"
        batteryText.setTextColor(cGreen)
        batteryText.text = list.joinToString("\n") {
            (if (list.size > 1) it.name + ": " else "Battery: ") + batteryOf(it)
        }
    }

    private val refresh = object : Runnable {
        override fun run() {
            refreshControllers()
            ui.postDelayed(this, 3000)
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresh)
    }

    override fun onPause() {
        ui.removeCallbacks(refresh)
        super.onPause()
    }

    private fun setDim(on: Boolean) {
        dim.visibility = if (on) View.VISIBLE else View.GONE
        val l = window.attributes
        l.screenBrightness = if (on) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = l
    }

    // ----- connection -----

    private fun connect() {
        val a = addr.text.toString().trim()
        val req = try { Request.Builder().url("ws://$a/ws").build() } catch (e: Exception) {
            setStatus("That address is not valid", cRed); return
        }
        setStatus("Connecting…", cAmber)
        go.text = "Cancel"
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                live = true
                ui.post {
                    setStatus("Connected. Press a button on your controller.", cGreen)
                    setRunning(true)
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val m = JSONObject(text)
                    if (m.has("rumble")) {
                        val amp = max(m.optDouble("strong", 0.0), m.optDouble("weak", 0.0)).toFloat()
                        ui.post { rumble(m.getInt("i"), amp) }
                    }
                } catch (_: Exception) { }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                live = false
                ui.post {
                    ws = null
                    pads.values.forEach { it.last = "" }
                    setStatus("Could not connect. Is the PC program running and the cable plugged in?", cRed)
                    setRunning(false)
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                live = false
                ui.post {
                    ws = null
                    pads.values.forEach { it.last = "" }
                    setStatus("Disconnected", cMute)
                    setRunning(false)
                }
            }
        })
    }

    private fun disconnect() {
        live = false
        ws?.close(1000, null)
        ws = null
        setStatus("Disconnected", cMute)
        setRunning(false)
    }

    override fun onDestroy() {
        ws?.close(1000, null)
        super.onDestroy()
    }

    // ----- input: only while connected, and only from gamepads -----

    private fun isPad(src: Int) =
        (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
        (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    private fun pad(deviceId: Int): Pad {
        pads[deviceId]?.let { return it }
        val p = Pad(pads.size)
        pads[deviceId] = p
        return p
    }

    private fun keyIndex(k: Int) = when (k) {
        KeyEvent.KEYCODE_BUTTON_A -> 0
        KeyEvent.KEYCODE_BUTTON_B -> 1
        KeyEvent.KEYCODE_BUTTON_X -> 2
        KeyEvent.KEYCODE_BUTTON_Y -> 3
        KeyEvent.KEYCODE_BUTTON_L1 -> 4
        KeyEvent.KEYCODE_BUTTON_R1 -> 5
        KeyEvent.KEYCODE_BUTTON_L2 -> 6
        KeyEvent.KEYCODE_BUTTON_R2 -> 7
        KeyEvent.KEYCODE_BUTTON_SELECT -> 8
        KeyEvent.KEYCODE_BUTTON_START -> 9
        KeyEvent.KEYCODE_BUTTON_THUMBL -> 10
        KeyEvent.KEYCODE_BUTTON_THUMBR -> 11
        KeyEvent.KEYCODE_DPAD_UP -> 12
        KeyEvent.KEYCODE_DPAD_DOWN -> 13
        KeyEvent.KEYCODE_DPAD_LEFT -> 14
        KeyEvent.KEYCODE_DPAD_RIGHT -> 15
        KeyEvent.KEYCODE_BUTTON_MODE -> 16
        else -> -1
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (live && isPad(e.source)) {
            val i = keyIndex(e.keyCode)
            if (i >= 0) {
                val p = pad(e.deviceId)
                if (e.action == KeyEvent.ACTION_DOWN) {
                    p.kb[i] = 1f
                    inputText.text = "Last input: ${names[i]}"
                } else if (e.action == KeyEvent.ACTION_UP) {
                    p.kb[i] = 0f
                }
                send(p)
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun ax(e: MotionEvent, axis: Int): Float {
        val v = e.getAxisValue(axis)
        return if (abs(v) < 0.08f) 0f else v
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (live && (e.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
            e.action == MotionEvent.ACTION_MOVE) {
            val p = pad(e.deviceId)
            p.a[0] = ax(e, MotionEvent.AXIS_X)
            p.a[1] = ax(e, MotionEvent.AXIS_Y)
            p.a[2] = ax(e, MotionEvent.AXIS_Z)
            p.a[3] = ax(e, MotionEvent.AXIS_RZ)
            p.mb[6] = max(ax(e, MotionEvent.AXIS_LTRIGGER), ax(e, MotionEvent.AXIS_BRAKE))
            p.mb[7] = max(ax(e, MotionEvent.AXIS_RTRIGGER), ax(e, MotionEvent.AXIS_GAS))
            val hx = e.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
            p.mb[12] = if (hy < -0.5f) 1f else 0f
            p.mb[13] = if (hy > 0.5f) 1f else 0f
            p.mb[14] = if (hx < -0.5f) 1f else 0f
            p.mb[15] = if (hx > 0.5f) 1f else 0f
            if (abs(p.a[0]) > 0.5f || abs(p.a[1]) > 0.5f) inputText.text = "Last input: left stick"
            else if (abs(p.a[2]) > 0.5f || abs(p.a[3]) > 0.5f) inputText.text = "Last input: right stick"
            else if (p.mb[6] > 0.5f) inputText.text = "Last input: L2"
            else if (p.mb[7] > 0.5f) inputText.text = "Last input: R2"
            send(p)
            return true
        }
        return super.dispatchGenericMotionEvent(e)
    }

    private fun r2(v: Float) = Math.round(v * 100) / 100f

    private fun send(p: Pad) {
        val sb = StringBuilder("{\"i\":").append(p.idx).append(",\"b\":[")
        for (n in 0 until 17) { if (n > 0) sb.append(','); sb.append(r2(max(p.kb[n], p.mb[n]))) }
        sb.append("],\"a\":[")
        for (n in 0 until 4) { if (n > 0) sb.append(','); sb.append(r2(p.a[n])) }
        sb.append("]}")
        val s = sb.toString()
        if (s != p.last) { p.last = s; ws?.send(s) }
    }

    @Suppress("DEPRECATION")
    private fun rumble(index: Int, amp: Float) {
        val id = pads.entries.firstOrNull { it.value.idx == index }?.key ?: return
        val d = InputDevice.getDevice(id) ?: return
        val v = if (Build.VERSION.SDK_INT >= 31) d.vibratorManager.defaultVibrator else d.vibrator
        if (amp <= 0f) { v.cancel(); return }
        if (v.hasVibrator()) {
            v.vibrate(VibrationEffect.createOneShot(100, (amp * 255).toInt().coerceIn(1, 255)))
        }
    }
}
