package com.gamewatchdog

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager

/**
 * Giám sát nhiều app cùng lúc (kể cả khi chia đôi màn hình / cửa sổ nổi).
 * App nào không còn hiện trên màn hình quá N giây (thoát hoặc văng) thì được mở lại,
 * chờ tải, rồi chạy macro riêng của app đó (lặp theo số lần đã cài).
 * Các macro chạy lần lượt, không chồng nhau.
 */
class WatchdogService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: WatchdogService? = null

        private val IGNORED = setOf("com.android.systemui")
        private const val LOOP_WINDOW_MS = 5 * 60_000L // khoảng thời gian đếm số lần mở lại
        private const val LOOP_MAX = 3                  // quá số lần này thì tự tắt giám sát app
    }

    private val handler = Handler(Looper.getMainLooper())

    private var topPkg = ""                              // app có cửa sổ mới nhất (theo sự kiện)
    private val awaySince = HashMap<String, Long>()      // app → thời điểm bắt đầu vắng mặt
    private val cooldownUntil = HashMap<String, Long>()  // app → không mở lại trước thời điểm này
    private val recoverTimes = HashMap<String, ArrayList<Long>>() // app → các lần tự mở lại gần đây
    private val busyApps = HashSet<String>()             // app đang mở lại / chờ tải / chạy macro
    private val tokens = HashMap<String, Int>()          // app → mã lượt chạy (để huỷ)
    private var tokenCounter = 0
    private val macroQueue = ArrayDeque<Pair<String, Int>>()
    private var macroRunning = false

    private var imePkgs: Set<String> = emptySet()
    private var floating: FloatingUi? = null

    private val ticker = object : Runnable {
        override fun run() {
            try {
                watch()
                floating?.refresh()
            } catch (e: Exception) {
                Prefs.log(this@WatchdogService, "Lỗi: ${e.message}")
            }
            handler.postDelayed(this, 2000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        refreshIme()
        Prefs.log(this, "Dịch vụ trợ năng đã kết nối")
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(ticker, 2000L)

        val ui = FloatingUi(this)
        floating = ui
        if (Prefs.isOverlay(this)) ui.show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Bỏ qua thanh thông báo và bàn phím để không nhầm là app đã thoát
        if (pkg in IGNORED || pkg in imePkgs) return
        // Bỏ qua nút nổi của chính app này (chỉ tính màn hình MainActivity)
        if (pkg == packageName && event.className?.toString()?.endsWith("MainActivity") != true) return
        topPkg = pkg
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        floating?.hide()
        floating = null
        awaySince.clear()
        cooldownUntil.clear()
        recoverTimes.clear()
        busyApps.clear()
        tokens.clear()
        macroQueue.clear()
        macroRunning = false
        if (instance === this) instance = null
    }

    private fun refreshIme() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imePkgs = imm.enabledInputMethodList.map { it.packageName }.toSet()
        } catch (e: Exception) {
            // bỏ qua
        }
    }

    // ---------------- API cho giao diện ----------------

    fun setOverlayVisible(visible: Boolean) {
        if (visible) floating?.show() else floating?.hide()
    }

    fun setWatchdogOn(on: Boolean) {
        Prefs.setEnabled(this, on)
        floating?.refresh()
    }

    fun isBusy(pkg: String): Boolean = pkg in busyApps

    /** "Thử ngay": mở lại app rồi chạy macro của app đó. Trả về false nếu app đang bận. */
    fun runNow(pkg: String): Boolean {
        if (pkg in busyApps) return false
        val app = Prefs.getApp(this, pkg) ?: return false
        recover(app, false, "thủ công")
        return true
    }

    /** Chỉ chạy macro của app trên màn hình hiện tại (không mở lại app). */
    fun playMacro(pkg: String): Boolean {
        if (pkg in busyApps) return false
        if (Prefs.getApp(this, pkg) == null) return false
        val token = ++tokenCounter
        tokens[pkg] = token
        busyApps.add(pkg)
        enqueueMacro(pkg, token)
        return true
    }

    /** Dừng macro đang chạy / đang chờ của app. */
    fun stopMacro(pkg: String) {
        tokens.remove(pkg)
        if (busyApps.remove(pkg)) Prefs.log(this, "${appLabel(pkg)}: đã dừng macro")
    }

    // ---------------- Giám sát ----------------

    /** Các package đang có cửa sổ hiển thị (đọc từ danh sách cửa sổ, hỗ trợ nhiều cửa sổ cùng lúc). */
    @Suppress("DEPRECATION")
    private fun visiblePackages(): Set<String> {
        val out = HashSet<String>()
        try {
            for (w in windows) {
                val r = w.root ?: continue
                val p = r.packageName?.toString()
                if (p != null) out.add(p)
                r.recycle()
            }
        } catch (e: Exception) {
            // bỏ qua
        }
        return out
    }

    private fun watch() {
        if (!Prefs.isEnabled(this) || floating?.recording == true) {
            awaySince.clear()
            return
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) { // màn hình tắt thì không làm gì
            awaySince.clear()
            return
        }
        if (topPkg == packageName) { // đang mở màn hình cài đặt của chính app này
            awaySince.clear()
            return
        }

        val apps = Prefs.getApps(this).filter { it.enabled }
        if (apps.isEmpty()) return

        val vis = visiblePackages()
        // Nếu đọc được cửa sổ của app khác thì tin vào danh sách cửa sổ, nếu không thì dùng sự kiện
        val reliable = vis.any { it !in IGNORED && it !in imePkgs && it != packageName }
        val now = System.currentTimeMillis()
        val grace = Prefs.getGraceSec(this) * 1000L

        for (a in apps) {
            if (a.pkg in busyApps) {
                awaySince.remove(a.pkg)
                continue
            }
            // Coi là còn chạy nếu thấy trong danh sách cửa sổ HOẶC là app vừa hiện lên gần nhất.
            // Game (Unity...) hay có cửa sổ không đọc được tên, nên không được chỉ dựa vào danh sách cửa sổ.
            val present = a.pkg == topPkg || (reliable && a.pkg in vis)
            if (present) {
                awaySince.remove(a.pkg)
                continue
            }
            val since = awaySince.getOrPut(a.pkg) { now }
            if (now - since >= grace && now >= (cooldownUntil[a.pkg] ?: 0L)) {
                awaySince.remove(a.pkg)
                val seen = vis.filter { it !in IGNORED && it != packageName }.joinToString()
                recover(a, true, "cửa sổ thấy: [$seen], trên cùng: $topPkg")
            }
        }
    }

    private fun recover(a: AppConfig, auto: Boolean, reason: String) {
        if (auto) {
            // Chặn vòng lặp: mở lại quá nhiều lần liên tiếp nghĩa là không nhận ra app đang chạy
            val now = System.currentTimeMillis()
            val list = recoverTimes.getOrPut(a.pkg) { ArrayList() }
            list.removeAll { now - it > LOOP_WINDOW_MS }
            if (list.size >= LOOP_MAX) {
                Prefs.log(
                    this,
                    "${a.label}: đã mở lại ${list.size} lần trong 5 phút → tự tắt giám sát app này " +
                        "(có thể không nhận ra app đang chạy). $reason"
                )
                Prefs.updateApp(this, a.pkg) { it.copy(enabled = false) }
                list.clear()
                return
            }
            list.add(now)
        }

        val token = ++tokenCounter
        tokens[a.pkg] = token
        busyApps.add(a.pkg)
        Prefs.log(this, "${a.label}: mở lại ($reason)")

        if (!launchApp(a.pkg)) {
            Prefs.log(this, "${a.label}: không mở được (đã gỡ cài đặt?). Tắt giám sát app này.")
            Prefs.updateApp(this, a.pkg) { it.copy(enabled = false) }
            busyApps.remove(a.pkg)
            tokens.remove(a.pkg)
            return
        }

        val wait = a.startupSec * 1000L
        cooldownUntil[a.pkg] = System.currentTimeMillis() + wait + 30000L
        Prefs.log(this, "${a.label}: chờ tải ${a.startupSec}s rồi chạy macro")
        handler.postDelayed({
            if (tokens[a.pkg] == token) enqueueMacro(a.pkg, token)
        }, wait)
    }

    private fun launchApp(pkg: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(intent)
        return true
    }

    // ---------------- Hàng đợi và chạy macro ----------------

    private fun enqueueMacro(pkg: String, token: Int) {
        macroQueue.addLast(Pair(pkg, token))
        pump()
    }

    /** Mỗi lúc chỉ chạy một macro để các cử chỉ không chồng lên nhau. */
    private fun pump() {
        if (macroRunning) return
        val job = macroQueue.removeFirstOrNull() ?: return
        if (tokens[job.first] != job.second) { // đã bị huỷ
            pump()
            return
        }
        macroRunning = true
        runMacro(job.first, job.second)
    }

    private fun appLabel(pkg: String): String = Prefs.getApp(this, pkg)?.label ?: pkg

    private fun runMacro(pkg: String, token: Int) {
        val app = Prefs.getApp(this, pkg)
        val steps = if (app == null) emptyList() else Prefs.parseMacro(app.macro)
        if (steps.isEmpty()) {
            finishRun(pkg, "chưa có macro (hãy ghi bằng nút ⏺)")
            return
        }
        val rounds = (app?.repeat ?: 1).coerceIn(1, 9999)
        val gapMs = (app?.repeatGapSec ?: 0).coerceAtLeast(0) * 1000L
        Prefs.log(this, "${appLabel(pkg)}: bắt đầu macro ${steps.size} bước × $rounds lần")
        doStep(pkg, steps, 0, token, 1, rounds, gapMs)
    }

    private fun doStep(
        pkg: String, steps: List<MacroStep>, index: Int, token: Int,
        round: Int, rounds: Int, gapMs: Long
    ) {
        if (tokens[pkg] != token) { // đã bị dừng
            macroRunning = false
            pump()
            return
        }
        if (index >= steps.size) {
            if (round < rounds) {
                handler.postDelayed({
                    doStep(pkg, steps, 0, token, round + 1, rounds, gapMs)
                }, gapMs)
            } else {
                finishRun(pkg, "xong macro ($rounds lần)")
            }
            return
        }
        val s = steps[index]
        handler.postDelayed({
            if (tokens[pkg] == token) {
                dispatchStep(s) { doStep(pkg, steps, index + 1, token, round, rounds, gapMs) }
            } else {
                macroRunning = false
                pump()
            }
        }, s.delayMs)
    }

    private fun finishRun(pkg: String, msg: String) {
        Prefs.log(this, "${appLabel(pkg)}: $msg")
        busyApps.remove(pkg)
        tokens.remove(pkg)
        // Sau khi macro xong, cho app thêm thời gian ổn định trước khi coi là vắng mặt
        cooldownUntil[pkg] = maxOf(cooldownUntil[pkg] ?: 0L, System.currentTimeMillis() + 20000L)
        macroRunning = false
        pump()
    }

    private fun dispatchStep(s: MacroStep, done: () -> Unit) {
        val size = screenSize()
        val w = size.first
        val h = size.second
        dispatchPx(
            w * s.x1 / 100f, h * s.y1 / 100f,
            w * s.x2 / 100f, h * s.y2 / 100f,
            s.durMs, s.kind == KIND_SWIPE, done
        )
    }

    /** Chạm hoặc vuốt theo toạ độ pixel. done() được gọi khi cử chỉ xong hoặc bị huỷ. */
    fun dispatchPx(
        x1: Float, y1: Float, x2: Float, y2: Float,
        durMs: Long, swipe: Boolean, done: () -> Unit
    ) {
        val path = Path()
        path.moveTo(x1, y1)
        if (swipe) path.lineTo(x2, y2)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durMs.coerceAtLeast(1L)))
            .build()
        val dispatched = dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    done()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    done()
                }
            },
            null
        )
        if (!dispatched) done()
    }

    @Suppress("DEPRECATION")
    fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val m = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(m)
            Pair(m.widthPixels, m.heightPixels)
        }
    }
}
