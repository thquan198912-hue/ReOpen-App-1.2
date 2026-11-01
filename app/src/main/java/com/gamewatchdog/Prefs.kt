package com.gamewatchdog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val KIND_TAP = 0
const val KIND_SWIPE = 1

/**
 * Một thao tác trong macro. Toạ độ theo % màn hình (0–100).
 * delayMs: chờ trước khi thực hiện. durMs: thời gian giữ (chạm) hoặc thời gian vuốt.
 */
data class MacroStep(
    val kind: Int,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val delayMs: Long,
    val durMs: Long
)

/** Một app được giám sát, có macro và thời gian chờ tải riêng. */
data class AppConfig(
    val pkg: String,
    val label: String,
    val startupSec: Int = 25,
    val macro: String = "",
    val enabled: Boolean = true,
    val repeat: Int = 1,          // số lần chạy macro
    val repeatGapSec: Int = 0     // nghỉ giữa các lần (giây)
)

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("watchdog", Context.MODE_PRIVATE)

    // ---- Cài đặt chung ----
    fun getGraceSec(c: Context): Int = sp(c).getInt("grace", 5)
    fun setGraceSec(c: Context, v: Int) = sp(c).edit().putInt("grace", v).apply()

    fun isEnabled(c: Context): Boolean = sp(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    fun isOverlay(c: Context): Boolean = sp(c).getBoolean("overlay", true)
    fun setOverlay(c: Context, v: Boolean) = sp(c).edit().putBoolean("overlay", v).apply()

    fun getPanelX(c: Context, def: Int): Int = sp(c).getInt("px", def)
    fun getPanelY(c: Context, def: Int): Int = sp(c).getInt("py", def)
    fun setPanelPos(c: Context, x: Int, y: Int) = sp(c).edit().putInt("px", x).putInt("py", y).apply()

    /** App đang được chọn trên nút nổi để ghi / chạy macro. */
    fun getTarget(c: Context): String = sp(c).getString("target", "") ?: ""
    fun setTarget(c: Context, v: String) = sp(c).edit().putString("target", v).apply()

    fun getLog(c: Context): String = sp(c).getString("log", "") ?: ""

    @Synchronized
    fun log(c: Context, msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val lines = (getLog(c).lines().filter { it.isNotBlank() } + "$t  $msg").takeLast(60)
        sp(c).edit().putString("log", lines.joinToString("\n")).apply()
    }

    // ---- Danh sách app ----
    @Synchronized
    fun getApps(c: Context): List<AppConfig> {
        val s = sp(c).getString("apps", null)
        if (s != null) return fromJson(s)
        // Chuyển từ bản cũ (chỉ 1 game)
        val oldPkg = sp(c).getString("pkg", "") ?: ""
        if (oldPkg.isEmpty()) return emptyList()
        val migrated = listOf(
            AppConfig(
                oldPkg, oldPkg,
                sp(c).getInt("startup", 25),
                sp(c).getString("taps", "") ?: "",
                true
            )
        )
        setApps(c, migrated)
        return migrated
    }

    @Synchronized
    fun setApps(c: Context, list: List<AppConfig>) {
        sp(c).edit().putString("apps", toJson(list)).apply()
    }

    fun getApp(c: Context, pkg: String): AppConfig? = getApps(c).firstOrNull { it.pkg == pkg }

    @Synchronized
    fun updateApp(c: Context, pkg: String, f: (AppConfig) -> AppConfig) {
        setApps(c, getApps(c).map { if (it.pkg == pkg) f(it) else it })
    }

    @Synchronized
    fun addApp(c: Context, app: AppConfig) {
        val list = getApps(c)
        if (list.none { it.pkg == app.pkg }) setApps(c, list + app)
    }

    @Synchronized
    fun removeApp(c: Context, pkg: String) {
        setApps(c, getApps(c).filter { it.pkg != pkg })
    }

    private fun toJson(list: List<AppConfig>): String {
        val arr = JSONArray()
        for (a in list) {
            val o = JSONObject()
            o.put("pkg", a.pkg)
            o.put("label", a.label)
            o.put("startup", a.startupSec)
            o.put("macro", a.macro)
            o.put("on", a.enabled)
            o.put("repeat", a.repeat)
            o.put("gap", a.repeatGapSec)
            arr.put(o)
        }
        return arr.toString()
    }

    private fun fromJson(s: String): List<AppConfig> {
        val out = ArrayList<AppConfig>()
        try {
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pkg = o.getString("pkg")
                out.add(
                    AppConfig(
                        pkg,
                        o.optString("label", pkg),
                        o.optInt("startup", 25),
                        o.optString("macro", ""),
                        o.optBoolean("on", true),
                        o.optInt("repeat", 1).coerceAtLeast(1),
                        o.optInt("gap", 0).coerceAtLeast(0)
                    )
                )
            }
        } catch (e: Exception) {
            // dữ liệu hỏng thì coi như rỗng
        }
        return out
    }

    // ---- Macro: mỗi dòng một thao tác ----
    //   tap,x,y,chờ_ms,giữ_ms
    //   swipe,x1,y1,x2,y2,chờ_ms,thời_gian_vuốt_ms
    //   x,y,chờ_ms            (dạng cũ = chạm)
    //   dòng bắt đầu bằng # là ghi chú

    fun formatStep(s: MacroStep): String =
        if (s.kind == KIND_SWIPE) {
            String.format(Locale.US, "swipe,%.1f,%.1f,%.1f,%.1f,%d,%d", s.x1, s.y1, s.x2, s.y2, s.delayMs, s.durMs)
        } else {
            String.format(Locale.US, "tap,%.1f,%.1f,%d,%d", s.x1, s.y1, s.delayMs, s.durMs)
        }

    fun parseMacro(text: String): List<MacroStep> = text.lines().mapNotNull { parseLine(it) }

    private fun pct(v: Float): Float = v.coerceIn(0f, 100f)

    private fun parseLine(raw: String): MacroStep? {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return null
        val p = line.split(",").map { it.trim() }
        val head = p[0].lowercase()

        fun f(i: Int): Float? = p.getOrNull(i)?.toFloatOrNull()
        fun l(i: Int): Long? = p.getOrNull(i)?.toLongOrNull()

        return when (head) {
            "swipe" -> {
                val x1 = f(1) ?: return null
                val y1 = f(2) ?: return null
                val x2 = f(3) ?: return null
                val y2 = f(4) ?: return null
                MacroStep(
                    KIND_SWIPE, pct(x1), pct(y1), pct(x2), pct(y2),
                    (l(5) ?: 500L).coerceAtLeast(0L),
                    (l(6) ?: 300L).coerceIn(50L, 10000L)
                )
            }
            "tap" -> {
                val x = f(1) ?: return null
                val y = f(2) ?: return null
                MacroStep(
                    KIND_TAP, pct(x), pct(y), pct(x), pct(y),
                    (l(3) ?: 1000L).coerceAtLeast(0L),
                    (l(4) ?: 60L).coerceIn(30L, 5000L)
                )
            }
            else -> {
                val x = f(0) ?: return null
                val y = f(1) ?: return null
                MacroStep(
                    KIND_TAP, pct(x), pct(y), pct(x), pct(y),
                    (l(2) ?: 1000L).coerceAtLeast(0L),
                    60L
                )
            }
        }
    }
}
