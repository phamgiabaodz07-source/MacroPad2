package com.macropad

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.hypot

class MacroService : AccessibilityService() {

    companion object { var instance: MacroService? = null }

    private lateinit var wm: WindowManager
    private val h = Handler(Looper.getMainLooper())
    private val buttons = HashMap<String, View>()
    private val runners = HashMap<String, Runner>()
    private var overlay: View? = null
    private var toolWin: View? = null
    var editMode = false

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        instance = this
    }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() { stopAll() }
    override fun onDestroy() { hideAll(); closeOverlay(); closeTool(); instance = null; super.onDestroy() }

    // ---------- cửa sổ nổi ----------
    private fun lp(w: Int, hh: Int): WindowManager.LayoutParams {
        val p = WindowManager.LayoutParams(
            w, hh, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28)
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        return p
    }

    private fun showOverlay(v: View) { closeOverlay(); wm.addView(v, lp(-1, -1)); overlay = v }
    private fun closeOverlay() { overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }; overlay = null }
    private fun closeTool() { toolWin?.let { try { wm.removeView(it) } catch (_: Exception) {} }; toolWin = null }

    fun bringBack() {
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    // ---------- nút nổi ----------
    fun showAll() { hideAll(); Store.load(this).forEach { addButton(it) } }
    fun refresh() { if (buttons.isNotEmpty()) showAll() }
    fun hideAll() {
        stopAll()
        buttons.values.forEach { try { wm.removeView(it) } catch (_: Exception) {} }
        buttons.clear()
    }
    private fun stopAll() { runners.values.forEach { it.stop() }; runners.clear() }

    fun startEdit() {
        showAll(); editMode = true; closeTool()
        val b = Button(this)
        b.text = "✔ Xong chỉnh vị trí"
        b.setOnClickListener { editMode = false; bringBack(); closeTool() }
        val p = lp(-2, -2); p.x = 20; p.y = 80
        wm.addView(b, p); toolWin = b
    }

    private fun addButton(m: Macro) {
        val v = TextView(this)
        v.text = m.name.take(5); v.gravity = Gravity.CENTER
        v.setTextColor(Color.WHITE); v.textSize = 12f
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL; bg.setColor(0xFF1E88E5.toInt()); bg.setStroke(4, Color.WHITE)
        v.background = bg
        v.alpha = m.alpha / 100f
        val p = lp(m.btnSize, m.btnSize); p.x = m.btnX; p.y = m.btnY
        var sx = 0f; var sy = 0f; var out = false

        v.setOnTouchListener { _, e ->
            if (editMode) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX - p.x; sy = e.rawY - p.y }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = (e.rawX - sx).toInt(); p.y = (e.rawY - sy).toInt()
                        wm.updateViewLayout(v, p)
                    }
                    MotionEvent.ACTION_UP -> { m.btnX = p.x; m.btnY = p.y; Store.updatePos(this, m.id, p.x, p.y) }
                }
            } else {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; out = false; bg.setColor(0xFFFFA000.toInt()); v.alpha = 1f; down(m) }
                    MotionEvent.ACTION_MOVE -> if (m.mode == 2 && !out) {
                        if (m.dynamic) {
                            // Nút động: nút đi theo ngón tay, độ lệch được chuyển thành vuốt tâm
                            val dx = e.rawX - sx; val dy = e.rawY - sy
                            runners[m.id]?.let { it.ax = dx; it.ay = dy }
                            p.x = m.btnX + dx.toInt(); p.y = m.btnY + dy.toInt()
                            wm.updateViewLayout(v, p)
                        } else {
                            // Nút tĩnh: kéo ngón ra ngoài nút thì dừng
                            val s = m.btnSize * 0.2f
                            if (e.x < -s || e.y < -s || e.x > v.width + s || e.y > v.height + s) {
                                out = true; runners[m.id]?.stop()
                            }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        bg.setColor(0xFF1E88E5.toInt()); v.alpha = m.alpha / 100f
                        if (m.mode == 2) runners[m.id]?.stop()
                        if (m.dynamic) { p.x = m.btnX; p.y = m.btnY; wm.updateViewLayout(v, p) }
                    }
                }
            }
            true
        }
        wm.addView(v, p); buttons[m.id] = v
    }

    private fun down(m: Macro) {
        val r = runners.getOrPut(m.id) { Runner(m) }
        if (m.mode == 1 && r.running) r.stop() else r.start()
    }

    // ---------- ghi thao tác ----------
    fun record(cb: (MutableList<Step>) -> Unit) {
        val steps = mutableListOf<Step>()
        var t0 = 0L; var lastUp = 0L; var sx = 0f; var sy = 0f
        val root = FrameLayout(this)
        root.setBackgroundColor(0x33FF0000)
        val hint = TextView(this)
        hint.text = "● Đang ghi\nChạm / vuốt trên màn hình\n(1 ngón)"
        hint.setTextColor(Color.WHITE); hint.textSize = 18f; hint.gravity = Gravity.CENTER
        root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        val stop = Button(this); stop.text = "⏹ Dừng ghi"
        stop.setOnClickListener {
            if (steps.isNotEmpty()) steps.last().delay = 100
            cb(steps); bringBack(); closeOverlay()
        }
        root.addView(stop, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        root.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    t0 = e.eventTime; sx = e.rawX; sy = e.rawY
                    if (steps.isNotEmpty()) steps.last().delay = (t0 - lastUp).coerceAtLeast(0L)
                }
                MotionEvent.ACTION_UP -> {
                    lastUp = e.eventTime
                    val d = lastUp - t0
                    val moved = hypot(e.rawX - sx, e.rawY - sy) > 30f
                    steps.add(
                        if (moved) Step("SWIPE", sx, sy, e.rawX, e.rawY, d.coerceAtLeast(30L), 0L)
                        else Step("TAP", sx, sy, sx, sy, d.coerceAtLeast(20L), 0L)
                    )
                }
            }
            true
        }
        showOverlay(root)
    }

    // ---------- chọn điểm trên màn hình ----------
    fun pick(cb: (Float, Float) -> Unit) {
        val root = FrameLayout(this)
        root.setBackgroundColor(0x5500AAFF)
        val t = TextView(this)
        t.text = "Chạm vào vị trí cần chọn"; t.setTextColor(Color.WHITE); t.textSize = 20f
        root.addView(t, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                cb(e.rawX, e.rawY); bringBack(); closeOverlay()
            }
            true
        }
        showOverlay(root)
    }

    // ---------- bộ chạy macro ----------
    inner class Runner(val m: Macro) {
        var running = false
        var ax = 0f; var ay = 0f
        private var i = 0; private var n = 0; private var sub = 0; private var fails = 0; private var gen = 0
        private var stroke: StrokeDescription? = null
        private var lx = 0f; private var ly = 0f
        private val aim get() = m.dynamic && m.mode == 2

        /** Các bước có "cùng lúc" liền sau bước i được gộp vào một cử chỉ nhiều ngón */
        private fun groupEnd(a: Int): Int {
            if (m.steps[a].type == "CLICK") return a
            var j = a
            while (j + 1 < m.steps.size && m.steps[j + 1].together && m.steps[j + 1].type != "CLICK") j++
            return j
        }
        private fun cycles(s: Step) = maxOf(1L, s.dur / maxOf(1L, s.hold + s.gap)).toInt()

        fun start() {
            if (running || m.steps.isEmpty()) return
            running = true; gen++; i = 0; n = 0; sub = 0; fails = 0; ax = 0f; ay = 0f; stroke = null
            step()
        }

        fun stop() { if (!running) return; running = false; release() }

        private fun release() {
            val st = stroke ?: return
            stroke = null
            try {
                val b = GestureDescription.Builder()
                b.addStroke(st.continueStroke(Path().also { it.moveTo(lx, ly) }, 0L, 10L, false))
                dispatchGesture(b.build(), null, null)
            } catch (_: Exception) {}
        }

        private fun step() {
            if (!running) return
            val my = gen
            try {
                val dm = resources.displayMetrics
                val b = GestureDescription.Builder()
                var cnt = 0
                var g = 16L
                val j = groupEnd(i)
                for (k in i..j) {
                    val s = m.steps[k]
                    if (s.type == "CLICK") {
                        val hold = s.hold.coerceIn(1L, 60000L)
                        b.addStroke(StrokeDescription(Path().also { it.moveTo(s.x, s.y) }, 0L, hold)); cnt++
                        g = maxOf(g, s.hold + s.gap)
                    } else {
                        val dur = s.dur.coerceIn(1L, 60000L)
                        when (s.type) {
                            "TAP" -> { b.addStroke(StrokeDescription(Path().also { it.moveTo(s.x, s.y) }, 0L, dur)); cnt++ }
                            "SWIPE" -> {
                                b.addStroke(StrokeDescription(Path().also { it.moveTo(s.x, s.y); it.lineTo(s.x2, s.y2) }, 0L, dur)); cnt++
                            }
                        }
                        g = maxOf(g, s.dur + s.delay)
                    }
                }
                g = g.coerceIn(16L, 60000L)

                if (aim) {
                    val tx = (m.anchorX + ax * m.sens).coerceIn(0f, dm.widthPixels - 1f)
                    val ty = (m.anchorY + ay * m.sens).coerceIn(0f, dm.heightPixels - 1f)
                    val prev = stroke
                    if (prev == null) { lx = tx; ly = ty }
                    val p = Path(); p.moveTo(lx, ly)
                    if (tx != lx || ty != ly) p.lineTo(tx, ty)
                    val st = if (prev == null) StrokeDescription(p, 0L, g, true) else prev.continueStroke(p, 0L, g, true)
                    stroke = st; lx = tx; ly = ty
                    b.addStroke(st); cnt++
                }

                if (cnt == 0) { h.postDelayed({ advance(my) }, g); return }

                val ok = dispatchGesture(b.build(), object : GestureResultCallback() {
                    override fun onCompleted(d: GestureDescription?) { advance(my) }
                    override fun onCancelled(d: GestureDescription?) {
                        if (my != gen || !running) return
                        stroke = null
                        if (++fails < 5) h.postDelayed({ if (my == gen) step() }, 30) else stop()
                    }
                }, null)
                if (!ok) stop()
            } catch (e: Exception) { stop() }
        }

        private fun advance(my: Int) {
            if (my != gen || !running) return
            fails = 0
            val s = m.steps[i]
            if (s.type == "CLICK") {
                sub++
                if (sub < cycles(s)) { step(); return }
                sub = 0
            }
            i = groupEnd(i) + 1
            if (i >= m.steps.size) {
                i = 0; n++
                if (m.mode != 2 && (m.mode == 0 && n >= maxOf(m.loops, 1) || m.mode == 1 && m.loops > 0 && n >= m.loops)) {
                    running = false; release(); return
                }
            }
            step()
        }
    }
}
