package com.macropad

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Collections
import org.json.JSONObject

class MainActivity : Activity() {

    private var macros = mutableListOf<Macro>()
    private var cur: Macro? = null
    private var isNew = false
    private val modes = arrayOf("Bấm 1 lần (chạy 1 lượt)", "Bấm bật / bấm tắt (lặp)", "Giữ để chạy, thả để dừng")

    override fun onCreate(s: Bundle?) { super.onCreate(s); showList() }
    override fun onResume() { super.onResume(); if (cur == null) showList() }

    // ---------- tiện ích giao diện ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    private fun n(v: Float) = v.toInt().toString()

    private fun newRoot(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(dp(14), dp(40), dp(14), dp(24))
        setContentView(ScrollView(this).also { it.addView(l) })
        return l
    }
    private fun tv(t: String, sz: Float = 15f) = TextView(this).also {
        it.text = t; it.textSize = sz; it.setPadding(0, dp(8), 0, dp(2))
    }
    private fun btn(t: String, f: () -> Unit) = Button(this).also {
        it.text = t; it.isAllCaps = false; it.setOnClickListener { _ -> f() }
    }
    private fun edit(v: String) = EditText(this).also { it.setText(v); it.setSingleLine() }
    private fun num(v: String) = edit(v).also {
        it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
    }
    private fun rowOf(vararg vs: View): LinearLayout {
        val l = LinearLayout(this); l.orientation = LinearLayout.HORIZONTAL
        vs.forEach { l.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        return l
    }
    private fun saveAll() { Store.save(this, macros); MacroService.instance?.refresh() }

    // ---------- danh sách macro ----------
    private fun showList() {
        cur = null; isNew = false
        macros = Store.load(this)
        val r = newRoot()
        val svc = MacroService.instance
        r.addView(tv("MacroPad", 26f))
        if (svc == null) {
            r.addView(tv("⚠️ Chưa bật dịch vụ trợ năng.\nVào Cài đặt → Trợ năng → MacroPad → Bật."))
            r.addView(btn("Mở cài đặt trợ năng") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        } else r.addView(tv("✅ Dịch vụ trợ năng đã bật"))

        r.addView(rowOf(
            btn("▶ Hiện nút nổi") {
                val s = MacroService.instance
                if (s == null) toast("Hãy bật dịch vụ trợ năng trước")
                else { s.editMode = false; s.showAll(); moveTaskToBack(true) }
            },
            btn("⏹ Ẩn nút") { MacroService.instance?.hideAll() }
        ))
        r.addView(btn("✥ Chỉnh vị trí nút nổi (kéo thả)") {
            val s = MacroService.instance
            if (s == null) toast("Hãy bật dịch vụ trợ năng trước")
            else { s.startEdit(); moveTaskToBack(true) }
        })
        r.addView(btn("+ Tạo macro mới") { isNew = true; showEditor(Macro()) })
        r.addView(btn("⬇ Nhập macro (dán mã)") { importDialog() })

        r.addView(tv("Danh sách macro (${macros.size})", 18f))
        macros.forEach { m ->
            r.addView(tv("${m.name}  ·  ${modes[m.mode.coerceIn(0, 2)]}${if (m.dynamic) " · động" else ""}  ·  ${m.steps.size} bước"))
            r.addView(rowOf(
                btn("Sửa") { showEditor(m) },
                btn("Nhân bản") {
                    val c = Macro.from(m.toJson())
                    c.id = System.currentTimeMillis().toString(); c.name = m.name + " (2)"
                    macros.add(c); saveAll(); showList()
                },
                btn("Xoá") {
                    AlertDialog.Builder(this).setMessage("Xoá macro \"${m.name}\"?")
                        .setPositiveButton("Xoá") { _, _ -> macros.remove(m); saveAll(); showList() }
                        .setNegativeButton("Huỷ", null).show()
                }
            ))
            r.addView(rowOf(
                btn("Đổi tên") { renameDialog(m) },
                btn("Chia sẻ") { shareMacro(m) }
            ))
        }
    }

    private fun renameDialog(m: Macro) {
        val e = edit(m.name)
        AlertDialog.Builder(this).setTitle("Đổi tên").setView(e)
            .setPositiveButton("OK") { _, _ -> m.name = e.text.toString().ifBlank { m.name }; saveAll(); showList() }
            .setNegativeButton("Huỷ", null).show()
    }

    private fun shareMacro(m: Macro) {
        val code = m.toJson().toString()
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("macro", code))
        toast("Đã sao chép mã macro")
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, code), "Chia sẻ macro"))
    }

    private fun importDialog() {
        val e = edit("")
        e.setSingleLine(false); e.minLines = 4
        val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cb.primaryClip?.let { if (it.itemCount > 0) e.setText(it.getItemAt(0).text ?: "") }
        AlertDialog.Builder(this).setTitle("Dán mã macro").setView(e)
            .setPositiveButton("Nhập") { _, _ ->
                try {
                    val c = Macro.from(JSONObject(e.text.toString().trim()))
                    c.id = System.currentTimeMillis().toString()
                    macros.add(c); saveAll(); showList()
                } catch (x: Exception) { toast("Mã macro không hợp lệ") }
            }
            .setNegativeButton("Huỷ", null).show()
    }

    // ---------- trình chỉnh macro ----------
    private fun showEditor(m: Macro) {
        cur = m
        val r = newRoot()
        r.addView(tv(if (isNew) "Macro mới" else "Sửa macro", 22f))

        val name = edit(m.name); r.addView(tv("Tên")); r.addView(name)

        r.addView(tv("Cách kích hoạt"))
        val mode = Spinner(this)
        mode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes)
        mode.setSelection(m.mode.coerceIn(0, 2)); r.addView(mode)

        val dyn = CheckBox(this)
        dyn.text = "Nút động (chỉ chế độ Giữ): kéo ngón ra ngoài thì nút đi theo và vuốt tâm"
        dyn.isChecked = m.dynamic; r.addView(dyn)

        val loops = num(m.loops.toString())
        r.addView(tv("Số lần lặp (0 = vô hạn; dùng cho chế độ Bấm 1 lần / Bật-tắt)")); r.addView(loops)

        val sens = num(m.sens.toString())
        r.addView(tv("Độ nhạy tâm (1.0 = ngón kéo bao nhiêu, tâm vuốt bấy nhiêu)")); r.addView(sens)

        val ax = num(n(m.anchorX)); val ay = num(n(m.anchorY))
        r.addView(tv("Điểm đặt ngón vuốt tâm (X, Y) – vùng xoay camera của game"))
        r.addView(rowOf(ax, ay))
        r.addView(btn("Chọn điểm này trên màn hình") { pick(ax, ay) })

        val size = num(m.btnSize.toString()); val alpha = num(m.alpha.toString())
        r.addView(tv("Kích thước nút (px) và độ đậm (10–100)"))
        r.addView(rowOf(size, alpha))

        val collect: () -> Unit = {
            m.name = name.text.toString().ifBlank { "Macro" }
            m.mode = mode.selectedItemPosition
            m.dynamic = dyn.isChecked
            m.loops = loops.text.toString().toIntOrNull() ?: 1
            m.sens = sens.text.toString().toFloatOrNull() ?: 1f
            m.anchorX = ax.text.toString().toFloatOrNull() ?: m.anchorX
            m.anchorY = ay.text.toString().toFloatOrNull() ?: m.anchorY
            m.btnSize = (size.text.toString().toIntOrNull() ?: 170).coerceIn(60, 400)
            m.alpha = (alpha.text.toString().toIntOrNull() ?: 75).coerceIn(10, 100)
        }

        r.addView(tv("Các bước (${m.steps.size})", 18f))
        m.steps.forEachIndexed { i, s ->
            r.addView(tv("${i + 1}. ${describe(s)}", 13f))
            r.addView(rowOf(
                btn("Sửa") { collect(); stepDialog(s) { showEditor(m) } },
                btn("↑") { if (i > 0) { collect(); Collections.swap(m.steps, i, i - 1); showEditor(m) } },
                btn("↓") { if (i < m.steps.size - 1) { collect(); Collections.swap(m.steps, i, i + 1); showEditor(m) } },
                btn("✕") { collect(); m.steps.removeAt(i); showEditor(m) }
            ))
        }
        r.addView(btn("+ Thêm bước") { collect(); val s = Step(); stepDialog(s) { m.steps.add(s); showEditor(m) } })
        r.addView(btn("+ Chạm liên tục (full auto)") { collect(); val s = Step(type = "CLICK", dur = 1000L, delay = 0L); stepDialog(s) { m.steps.add(s); showEditor(m) } })
        r.addView(btn("⏺ Ghi thao tác (thay các bước hiện có)") {
            val svc = MacroService.instance
            if (svc == null) toast("Hãy bật dịch vụ trợ năng trước")
            else {
                collect(); moveTaskToBack(true)
                svc.record { steps -> m.steps = steps; showEditor(m) }
            }
        })

        r.addView(tv(""))
        r.addView(rowOf(
            btn("💾 Lưu") {
                collect()
                if (isNew) macros.add(m)
                saveAll(); showList()
            },
            btn("Huỷ") { showList() }
        ))
    }

    private fun describe(s: Step): String {
        val t = when (s.type) {
            "SWIPE" -> "VUỐT (${n(s.x)},${n(s.y)})→(${n(s.x2)},${n(s.y2)}) ${s.dur}ms, chờ ${s.delay}ms"
            "WAIT" -> "CHỜ ${s.dur}ms, thêm ${s.delay}ms"
            "CLICK" -> "CHẠM LIÊN TỤC (${n(s.x)},${n(s.y)}) tổng ${s.dur}ms, chạm ${s.hold}ms, nghỉ ${s.gap}ms"
            else -> "CHẠM/GIỮ (${n(s.x)},${n(s.y)}) giữ ${s.dur}ms, chờ ${s.delay}ms"
        }
        return if (s.together) "⫘ cùng lúc · $t" else t
    }

    private fun pick(ex: EditText, ey: EditText) {
        val svc = MacroService.instance
        if (svc == null) { toast("Hãy bật dịch vụ trợ năng trước"); return }
        moveTaskToBack(true)
        svc.pick { x, y -> ex.setText(x.toInt().toString()); ey.setText(y.toInt().toString()) }
    }

    private fun stepDialog(s: Step, done: () -> Unit) {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL; l.setPadding(dp(16), dp(8), dp(16), 0)
        val types = arrayOf("TAP", "SWIPE", "WAIT", "CLICK")
        val type = Spinner(this)
        type.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("TAP – chạm / giữ", "SWIPE – vuốt", "WAIT – chờ", "CLICK – chạm liên tục"))
        type.setSelection(types.indexOf(s.type).coerceAtLeast(0)); l.addView(type)

        val x = num(n(s.x)); val y = num(n(s.y)); val x2 = num(n(s.x2)); val y2 = num(n(s.y2))
        val dur = num(s.dur.toString()); val delay = num(s.delay.toString())
        val hold = num(s.hold.toString()); val gap = num(s.gap.toString())
        val tog = CheckBox(this); tog.text = "Chạy cùng lúc với bước trước (thêm 1 ngón tay)"; tog.isChecked = s.together
        l.addView(tv("Điểm 1 (X, Y)")); l.addView(rowOf(x, y))
        l.addView(btn("Chọn điểm 1 trên màn hình") { pick(x, y) })
        l.addView(tv("Điểm 2 (chỉ cho VUỐT)")); l.addView(rowOf(x2, y2))
        l.addView(btn("Chọn điểm 2 trên màn hình") { pick(x2, y2) })
        l.addView(tv("Thời gian giữ / vuốt / chờ (ms). Với CLICK: tổng thời lượng")); l.addView(dur)
        l.addView(tv("Chờ sau bước này (ms)")); l.addView(delay)
        l.addView(tv("Chỉ cho CLICK: mỗi lần chạm / nghỉ giữa hai lần (ms)")); l.addView(rowOf(hold, gap))
        l.addView(tog)

        AlertDialog.Builder(this).setTitle("Bước")
            .setView(ScrollView(this).also { it.addView(l) })
            .setPositiveButton("OK") { _, _ ->
                s.type = types[type.selectedItemPosition]
                s.x = x.text.toString().toFloatOrNull() ?: s.x
                s.y = y.text.toString().toFloatOrNull() ?: s.y
                s.x2 = x2.text.toString().toFloatOrNull() ?: s.x2
                s.y2 = y2.text.toString().toFloatOrNull() ?: s.y2
                s.dur = (dur.text.toString().toLongOrNull() ?: 40L).coerceAtLeast(1L)
                s.delay = (delay.text.toString().toLongOrNull() ?: 60L).coerceAtLeast(0L)
                s.hold = (hold.text.toString().toLongOrNull() ?: 50L).coerceAtLeast(1L)
                s.gap = (gap.text.toString().toLongOrNull() ?: 50L).coerceAtLeast(0L)
                s.together = tog.isChecked
                done()
            }
            .setNegativeButton("Huỷ", null).show()
    }
}
