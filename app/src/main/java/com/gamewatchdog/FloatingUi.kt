package com.gamewatchdog

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Thanh nút nổi nhỏ (kéo được, thu gọn được) và lớp ghi macro.
 * Dùng cửa sổ TYPE_ACCESSIBILITY_OVERLAY nên không cần thêm quyền "hiển thị trên ứng dụng khác".
 *
 *  ≡      kéo để di chuyển, chạm để thu gọn / mở rộng
 *  🟢/⚪   bật / tắt giám sát
 *  [tên]  chạm để đổi app đang chọn (macro ghi / chạy sẽ thuộc app này)
 *  ⏺/⏹   bắt đầu / kết thúc ghi macro cho app đang chọn
 *  ▶      chạy macro của app đang chọn ngay trên màn hình hiện tại
 */
@SuppressLint("ClickableViewAccessibility")
class FloatingUi(private val svc: WatchdogService) {

    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: LinearLayout? = null
    private var rootParams: WindowManager.LayoutParams? = null
    private var handleTv: TextView? = null
    private var watchTv: TextView? = null
    private var chipTv: TextView? = null
    private var recTv: TextView? = null
    private var playTv: TextView? = null
    private var collapsed = false

    private var layer: View? = null
    private var layerParams: WindowManager.LayoutParams? = null
    private val recorded = ArrayList<MacroStep>()
    private var lastEnd = 0L
    private var recPkg = ""

    var recording = false
        private set

    private fun dp(v: Int): Int = (v * svc.resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(svc, msg, Toast.LENGTH_SHORT).show()
    }

    private fun item(label: String, onClick: () -> Unit): TextView {
        val tv = TextView(svc)
        tv.text = label
        tv.textSize = 17f
        tv.gravity = Gravity.CENTER
        tv.setTextColor(Color.WHITE)
        tv.layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
        tv.setOnClickListener { onClick() }
        return tv
    }

    private fun chip(onClick: () -> Unit): TextView {
        val tv = TextView(svc)
        tv.text = "—"
        tv.textSize = 12f
        tv.gravity = Gravity.CENTER
        tv.setTextColor(Color.WHITE)
        tv.setPadding(dp(8), 0, dp(8), 0)
        tv.minWidth = dp(38)
        tv.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38))
        val bg = GradientDrawable()
        bg.cornerRadius = dp(14).toFloat()
        bg.setColor(Color.argb(120, 79, 195, 247))
        tv.background = bg
        tv.setOnClickListener { onClick() }
        return tv
    }

    // ---- App đang chọn ----

    private fun targetApp(): AppConfig? {
        val apps = Prefs.getApps(svc)
        if (apps.isEmpty()) return null
        val t = Prefs.getTarget(svc)
        return apps.firstOrNull { it.pkg == t } ?: apps[0]
    }

    private fun cycleTarget() {
        if (recording) {
            toast("Đang ghi, bấm ⏹ trước khi đổi app")
            return
        }
        val apps = Prefs.getApps(svc)
        if (apps.isEmpty()) {
            toast("Chưa có app nào, hãy thêm trong ứng dụng")
            return
        }
        val cur = apps.indexOfFirst { it.pkg == Prefs.getTarget(svc) }
        val next = apps[(cur + 1) % apps.size]
        Prefs.setTarget(svc, next.pkg)
        toast("Đang chọn: ${next.label}")
        refresh()
    }

    // ---- Dựng thanh nút ----

    fun show() {
        if (root != null) return

        val bg = GradientDrawable()
        bg.cornerRadius = dp(20).toFloat()
        bg.setColor(Color.argb(200, 20, 20, 20))

        val r = LinearLayout(svc)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.background = bg
        r.alpha = 0.85f
        r.setPadding(dp(2), dp(2), dp(2), dp(2))

        val handle = item("≡") { toggleCollapse() }
        val watch = item("⚪") { svc.setWatchdogOn(!Prefs.isEnabled(svc)) }
        val chipView = chip { cycleTarget() }
        val rec = item("⏺") { if (recording) stopRecording() else startRecording() }
        val play = item("▶") {
            val t = targetApp()
            if (recording) {
                toast("Đang ghi, bấm ⏹ trước")
            } else if (t == null) {
                toast("Chưa có app nào")
            } else if (svc.isBusy(t.pkg)) {
                svc.stopMacro(t.pkg)
                toast("Đã dừng macro ${t.label}")
            } else if (!svc.playMacro(t.pkg)) {
                toast("Không chạy được macro")
            }
        }
        r.addView(handle)
        r.addView(watch)
        r.addView(chipView)
        r.addView(rec)
        r.addView(play)

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = Prefs.getPanelX(svc, dp(8))
        p.y = Prefs.getPanelY(svc, dp(120))

        makeDraggable(handle, p, r)

        try {
            wm.addView(r, p)
        } catch (e: Exception) {
            Prefs.log(svc, "Không hiện được nút nổi: ${e.message}")
            return
        }
        root = r
        rootParams = p
        handleTv = handle
        watchTv = watch
        chipTv = chipView
        recTv = rec
        playTv = play
        collapsed = false
        refresh()
    }

    fun hide() {
        if (recording) stopRecording()
        val r = root
        if (r != null) {
            try {
                wm.removeView(r)
            } catch (e: Exception) {
                // đã bị gỡ
            }
        }
        root = null
        rootParams = null
    }

    fun refresh() {
        watchTv?.text = if (Prefs.isEnabled(svc)) "🟢" else "⚪"
        recTv?.text = if (recording) "⏹" else "⏺"
        val t = if (recording) Prefs.getApp(svc, recPkg) else targetApp()
        chipTv?.text = t?.label?.take(6) ?: "—"
        playTv?.text = if (t != null && svc.isBusy(t.pkg)) "🛑" else "▶"
    }

    private fun toggleCollapse() {
        collapsed = !collapsed
        val vis = if (collapsed) View.GONE else View.VISIBLE
        watchTv?.visibility = vis
        chipTv?.visibility = vis
        recTv?.visibility = vis
        playTv?.visibility = vis
        handleTv?.text = if (collapsed) "●" else "≡"
        val r = root
        val p = rootParams
        if (r != null && p != null) {
            try {
                wm.updateViewLayout(r, p)
            } catch (e: Exception) {
                // bỏ qua
            }
        }
    }

    private fun makeDraggable(handle: View, params: WindowManager.LayoutParams, target: View) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = e.rawX
                    touchY = e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    if (moved) {
                        params.x = startX + dx
                        params.y = startY + dy
                        try {
                            wm.updateViewLayout(target, params)
                        } catch (ex: Exception) {
                            // bỏ qua
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        Prefs.setPanelPos(svc, params.x, params.y)
                    } else {
                        handle.performClick()
                    }
                    true
                }
                else -> true
            }
        }
    }

    // ---------------- Ghi macro ----------------

    fun startRecording() {
        if (recording) return
        val t = targetApp()
        if (t == null) {
            toast("Chưa có app nào, hãy thêm app trong ứng dụng trước")
            return
        }
        recPkg = t.pkg
        recorded.clear()
        lastEnd = SystemClock.uptimeMillis()

        val v = View(svc)
        v.setBackgroundColor(Color.argb(40, 255, 0, 0)) // phủ nhẹ màu đỏ để biết đang ghi

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        var downX = 0f
        var downY = 0f
        var downT = 0L
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    downT = SystemClock.uptimeMillis()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val upT = SystemClock.uptimeMillis()
                    onUserGesture(downX, downY, e.rawX, e.rawY, upT - downT, downT, upT)
                    true
                }
                else -> true
            }
        }

        try {
            wm.addView(v, lp)
        } catch (e: Exception) {
            toast("Không bật được chế độ ghi")
            return
        }
        layer = v
        layerParams = lp
        recording = true
        bringPanelToFront()
        refresh()
        toast("Đang ghi macro cho ${t.label}. Xong bấm ⏹")
    }

    fun stopRecording() {
        if (!recording) return
        recording = false
        val v = layer
        if (v != null) {
            try {
                wm.removeView(v)
            } catch (e: Exception) {
                // đã bị gỡ
            }
        }
        layer = null
        layerParams = null

        val app = Prefs.getApp(svc, recPkg)
        if (app == null) {
            toast("App này đã bị xoá khỏi danh sách")
        } else if (recorded.isEmpty()) {
            toast("Chưa ghi thao tác nào, giữ nguyên macro cũ của ${app.label}")
        } else {
            val text = "# Ghi bằng nút nổi (${recorded.size} bước)\n" +
                recorded.joinToString("\n") { Prefs.formatStep(it) }
            Prefs.updateApp(svc, recPkg) { it.copy(macro = text) }
            toast("Đã lưu macro ${recorded.size} bước cho ${app.label}")
            Prefs.log(svc, "${app.label}: đã ghi macro ${recorded.size} bước")
        }
        refresh()
    }

    private fun bringPanelToFront() {
        val r = root
        val p = rootParams
        if (r == null || p == null) return
        try {
            wm.removeView(r)
            wm.addView(r, p)
        } catch (e: Exception) {
            // bỏ qua
        }
    }

    private fun onUserGesture(
        x1: Float, y1: Float, x2: Float, y2: Float,
        heldMs: Long, downT: Long, upT: Long
    ) {
        val size = svc.screenSize()
        val w = size.first
        val h = size.second
        val swipe = hypot(x2 - x1, y2 - y1) > dp(24)
        val dur = if (swipe) heldMs.coerceIn(100L, 5000L) else heldMs.coerceIn(40L, 3000L)
        val delay = (downT - lastEnd).coerceAtLeast(0L)
        lastEnd = upT + dur

        fun pct(v: Float, total: Int): Float = (v / total * 100f).coerceIn(0f, 100f)
        recorded.add(
            MacroStep(
                if (swipe) KIND_SWIPE else KIND_TAP,
                pct(x1, w), pct(y1, h), pct(x2, w), pct(y2, h),
                delay, dur
            )
        )

        // Cho thao tác "xuyên" qua lớp ghi để app vẫn nhận được: tạm thời không chặn chạm rồi phát lại
        val v = layer
        val lp = layerParams
        if (v != null && lp != null) {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            try {
                wm.updateViewLayout(v, lp)
            } catch (e: Exception) {
                // bỏ qua
            }
        }
        svc.dispatchPx(x1, y1, x2, y2, dur, swipe) {
            val v2 = layer
            val lp2 = layerParams
            if (v2 != null && lp2 != null) {
                lp2.flags = lp2.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                try {
                    wm.updateViewLayout(v2, lp2)
                } catch (e: Exception) {
                    // bỏ qua
                }
            }
        }
    }
}
