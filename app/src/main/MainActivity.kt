package com.padbridge

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.text.InputType
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
    private val client = OkHttpClient.Builder()
        .pingInterval(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var ws: WebSocket? = null
    @Volatile private var live = false
    private val pads = LinkedHashMap<Int, Pad>()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var addr: EditText
    private lateinit var go: Button
    private lateinit var dim: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = getSharedPreferences("p", Context.MODE_PRIVATE)

        fun label(t: String, size: Float, color: Int) = TextView(this).apply {
            text = t; textSize = size; setTextColor(color)
            setPadding(0, 16, 0, 16)
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 120, 48, 48)
            setBackgroundColor(Color.parseColor("#13161c"))
        }
        col.addView(label("Pad Bridge", 26f, Color.WHITE))
        col.addView(label("PC address (USB: localhost:8080)", 13f, Color.GRAY))
        addr = EditText(this).apply {
            setText(prefs.getString("addr", "localhost:8080"))
            setTextColor(Color.WHITE)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        col.addView(addr)
        go = Button(this).apply {
            text = "Start"
            setOnClickListener {
                if (ws != null) disconnect() else {
                    prefs.edit().putString("addr", addr.text.toString().trim()).apply()
                    connect()
                }
            }
        }
        col.addView(go)
        status = label("Not connected", 16f, Color.parseColor("#9aa4b2"))
        col.addView(status)
        col.addView(Button(this).apply {
            text = "Dim screen"
            setOnClickListener { setDim(true) }
        })
        col.addView(label(
            "1. Pair your controller in Android Bluetooth settings.\n" +
            "2. Plug the phone into the PC with USB debugging on, and start the PC program.\n" +
            "3. Type localhost:8080 above and tap Start.\n" +
            "4. Keep this app open in front while you play. Tap Dim screen to save battery; tap the black screen to come back.",
            13f, Color.GRAY))

        dim = View(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            setOnClickListener { setDim(false) }
        }
        val root = FrameLayout(this)
        root.addView(col)
        root.addView(dim, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setContentView(root)
    }

    private fun setDim(on: Boolean) {
        dim.visibility = if (on) View.VISIBLE else View.GONE
        val lp = window.attributes
        lp.screenBrightness = if (on) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    private fun connect() {
        val a = addr.text.toString().trim()
        val req = try { Request.Builder().url("ws://$a/ws").build() } catch (e: Exception) {
            status.text = "Bad address"; return
        }
        status.text = "Connecting…"
        go.text = "Cancel"
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                live = true
                ui.post { status.text = "Connected. Press a button on your controller."; go.text = "Stop" }
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
                    ws = null; pads.values.forEach { it.last = "" }
                    status.text = "Could not connect: ${t.message}"; go.text = "Start"
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                live = false
                ui.post { ws = null; pads.values.forEach { it.last = "" }; status.text = "Disconnected"; go.text = "Start" }
            }
        })
    }

    private fun disconnect() {
        live = false
        ws?.close(1000, null)
        ws = null
        status.text = "Disconnected"
        go.text = "Start"
    }

    override fun onDestroy() {
        ws?.close(1000, null)
        super.onDestroy()
    }

    // ----- input: only while connected, and only from gamepads -----

    private fun isPad(src: Int) =
        (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
        (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    private fun pad(deviceId: Int) = pads.getOrPut(deviceId) { Pad(pads.size) }

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
                if (e.action == KeyEvent.ACTION_DOWN) p.kb[i] = 1f
                else if (e.action == KeyEvent.ACTION_UP) p.kb[i] = 0f
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
