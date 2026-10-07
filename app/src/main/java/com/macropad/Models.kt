package com.macropad

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** type: TAP (chạm/giữ), SWIPE (vuốt), WAIT (chờ), CLICK (chạm liên tục: dur=tổng, hold=mỗi lần chạm, gap=nghỉ). together=chạy cùng lúc với bước trước. dur = thời gian giữ/vuốt/chờ, delay = chờ sau bước (ms) */
class Step(
    var type: String = "TAP",
    var x: Float = 500f, var y: Float = 500f,
    var x2: Float = 500f, var y2: Float = 500f,
    var dur: Long = 40L, var delay: Long = 60L,
    var hold: Long = 50L, var gap: Long = 50L, var together: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().put("t", type)
        .put("x", x.toDouble()).put("y", y.toDouble())
        .put("x2", x2.toDouble()).put("y2", y2.toDouble())
        .put("d", dur).put("w", delay).put("h", hold).put("g", gap).put("tg", together)

    companion object {
        fun from(o: JSONObject) = Step(
            o.optString("t", "TAP"),
            o.optDouble("x").toFloat(), o.optDouble("y").toFloat(),
            o.optDouble("x2").toFloat(), o.optDouble("y2").toFloat(),
            o.optLong("d", 40), o.optLong("w", 60),
            o.optLong("h", 50), o.optLong("g", 50), o.optBoolean("tg")
        )
    }
}

/** mode: 0 = bấm 1 lần, 1 = bấm bật/tắt, 2 = giữ để chạy */
class Macro(
    var id: String = System.currentTimeMillis().toString(),
    var name: String = "Macro mới",
    var mode: Int = 2,
    var dynamic: Boolean = false,
    var loops: Int = 1,
    var sens: Float = 1f,
    var anchorX: Float = 1500f,
    var anchorY: Float = 600f,
    var btnX: Int = 200,
    var btnY: Int = 500,
    var btnSize: Int = 170,
    var alpha: Int = 75,
    var steps: MutableList<Step> = mutableListOf()
) {
    fun toJson(): JSONObject {
        val a = JSONArray(); steps.forEach { a.put(it.toJson()) }
        return JSONObject().put("id", id).put("name", name).put("mode", mode)
            .put("dyn", dynamic).put("loops", loops).put("sens", sens.toDouble())
            .put("ax", anchorX.toDouble()).put("ay", anchorY.toDouble())
            .put("bx", btnX).put("by", btnY).put("bs", btnSize).put("al", alpha)
            .put("steps", a)
    }

    companion object {
        fun from(o: JSONObject): Macro {
            val a = o.optJSONArray("steps") ?: JSONArray()
            return Macro(
                o.optString("id"), o.optString("name"), o.optInt("mode", 2),
                o.optBoolean("dyn"), o.optInt("loops", 1), o.optDouble("sens", 1.0).toFloat(),
                o.optDouble("ax", 1500.0).toFloat(), o.optDouble("ay", 600.0).toFloat(),
                o.optInt("bx", 200), o.optInt("by", 500), o.optInt("bs", 170), o.optInt("al", 75),
                MutableList(a.length()) { Step.from(a.getJSONObject(it)) }
            )
        }
    }
}

object Store {
    private const val K = "macros"
    fun load(c: Context): MutableList<Macro> {
        val s = c.getSharedPreferences("mp", 0).getString(K, "[]") ?: "[]"
        return try {
            val a = JSONArray(s)
            MutableList(a.length()) { Macro.from(a.getJSONObject(it)) }
        } catch (e: Exception) { mutableListOf() }
    }
    fun save(c: Context, l: List<Macro>) {
        val a = JSONArray(); l.forEach { a.put(it.toJson()) }
        c.getSharedPreferences("mp", 0).edit().putString(K, a.toString()).apply()
    }
    fun updatePos(c: Context, id: String, x: Int, y: Int) {
        val l = load(c)
        l.find { it.id == id }?.let { it.btnX = x; it.btnY = y }
        save(c, l)
    }
}
