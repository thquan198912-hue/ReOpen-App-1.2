package com.gamewatchdog

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var tvStatus: TextView
    private lateinit var appsBox: LinearLayout
    private lateinit var etGrace: EditText
    private lateinit var swEnable: Switch
    private lateinit var tvLog: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refreshStatus()
            ui.postDelayed(this, 2000L)
        }
    }

    private val enableListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        saveFields()
        if (checked && Prefs.getApps(this).none { it.enabled }) {
            toast("Hãy thêm ít nhất một app trước")
            swEnable.isChecked = false
        } else {
            Prefs.setEnabled(this, checked)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(16), dp(16), dp(40))
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        root.addView(label("Game Watchdog", 24f, true))
        tvStatus = label("", 15f, false)
        root.addView(tvStatus)

        root.addView(button("1. Bật dịch vụ trợ năng") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(
            label(
                "Nút bật bị mờ (Android 13+)? Mở thông tin ứng dụng → ⋮ góc phải → \"Cho phép cài đặt bị hạn chế\", rồi quay lại bật dịch vụ. " +
                    "Sau khi cài bản mới, hãy tắt rồi bật lại dịch vụ.",
                13f, false
            )
        )
        root.addView(button("Mở thông tin ứng dụng") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        })

        root.addView(label("2. Các app được giám sát", 16f, true))
        root.addView(
            label(
                "Mỗi app có macro và thời gian chờ tải riêng. Chạy nhiều app cùng lúc bằng chia đôi màn hình hoặc cửa sổ nổi của máy, " +
                    "rồi thêm từng app vào danh sách.",
                13f, false
            )
        )
        appsBox = LinearLayout(this)
        appsBox.orientation = LinearLayout.VERTICAL
        root.addView(appsBox)
        root.addView(button("＋ Thêm app") { showPicker() })

        root.addView(label("3. Nút nổi trên màn hình", 16f, true))
        root.addView(
            label(
                "≡ kéo để di chuyển, chạm để thu gọn thành chấm nhỏ\n" +
                    "🟢/⚪ bật / tắt giám sát\n" +
                    "[tên app] chạm để đổi app đang chọn\n" +
                    "⏺ ghi macro cho app đang chọn, ⏹ kết thúc và lưu\n" +
                    "▶ chạy macro của app đang chọn ngay trên màn hình",
                13f, false
            )
        )
        root.addView(button("Hiện / ẩn nút nổi") {
            val v = !Prefs.isOverlay(this)
            Prefs.setOverlay(this, v)
            val s = WatchdogService.instance
            if (s == null) {
                toast("Hãy bật dịch vụ trợ năng trước")
            } else {
                s.setOverlayVisible(v)
                toast(if (v) "Đã hiện nút nổi" else "Đã ẩn nút nổi")
            }
        })

        root.addView(label("4. Cài đặt giám sát", 16f, true))
        root.addView(label("Số giây một app vắng mặt trước khi coi là đã thoát/văng", 13f, false))
        etGrace = EditText(this)
        etGrace.inputType = InputType.TYPE_CLASS_NUMBER
        etGrace.setSingleLine()
        root.addView(etGrace)
        root.addView(button("Lưu cài đặt") {
            saveFields()
            toast("Đã lưu")
        })

        swEnable = Switch(this)
        swEnable.text = "Bật giám sát"
        swEnable.textSize = 17f
        swEnable.setPadding(0, dp(12), 0, dp(12))
        root.addView(swEnable)

        root.addView(button("Tắt tối ưu pin (nên làm)") { requestBatteryExemption() })

        root.addView(label("Nhật ký (mới nhất ở trên)", 16f, true))
        tvLog = label("", 12f, false)
        root.addView(tvLog)
    }

    override fun onResume() {
        super.onResume()
        etGrace.setText(Prefs.getGraceSec(this).toString())
        swEnable.setOnCheckedChangeListener(null)
        swEnable.isChecked = Prefs.isEnabled(this)
        swEnable.setOnCheckedChangeListener(enableListener)
        renderApps()
        ui.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refresher)
        saveFields()
    }

    private fun saveFields() {
        Prefs.setGraceSec(this, (etGrace.text.toString().toIntOrNull() ?: 5).coerceIn(2, 600))
    }

    private fun refreshStatus() {
        val on = WatchdogService.instance != null
        tvStatus.text = if (on) {
            "● Dịch vụ trợ năng: ĐANG CHẠY"
        } else {
            "○ Dịch vụ trợ năng: CHƯA BẬT (bấm nút 1)"
        }

        val enabled = Prefs.isEnabled(this)
        if (swEnable.isChecked != enabled) {
            swEnable.setOnCheckedChangeListener(null)
            swEnable.isChecked = enabled
            swEnable.setOnCheckedChangeListener(enableListener)
        }

        val log = Prefs.getLog(this).lines().filter { it.isNotBlank() }.reversed().joinToString("\n")
        tvLog.text = if (log.isEmpty()) "(trống)" else log
    }

    // ---------------- Danh sách app ----------------

    private fun renderApps() {
        appsBox.removeAllViews()
        val apps = Prefs.getApps(this)
        if (apps.isEmpty()) {
            appsBox.addView(label("(chưa có app nào — bấm \"＋ Thêm app\")", 13f, false))
            return
        }
        for (a in apps) {
            val card = LinearLayout(this)
            card.orientation = LinearLayout.VERTICAL
            card.setPadding(dp(10), dp(8), dp(10), dp(8))
            val bg = GradientDrawable()
            bg.cornerRadius = dp(10).toFloat()
            bg.setStroke(dp(1), Color.LTGRAY)
            card.background = bg
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, dp(8), 0, 0)
            card.layoutParams = lp

            card.addView(label(a.label, 16f, true))
            val steps = Prefs.parseMacro(a.macro).size
            card.addView(label("${a.pkg}\nchờ tải ${a.startupSec}s  •  macro $steps bước  •  chạy ${a.repeat} lần", 12f, false))

            val cb = CheckBox(this)
            cb.text = "Giám sát app này"
            cb.isChecked = a.enabled
            cb.setOnCheckedChangeListener { _, checked ->
                Prefs.updateApp(this, a.pkg) { it.copy(enabled = checked) }
            }
            card.addView(cb)

            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.addView(rowButton("Macro") { editApp(a.pkg) })
            row.addView(rowButton("Thử") { testApp(a.pkg) })
            row.addView(rowButton("Xoá") { confirmRemove(a) })
            card.addView(row)

            appsBox.addView(card)
        }
    }

    private fun editApp(pkg: String) {
        val app = Prefs.getApp(this, pkg) ?: return

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(8), dp(16), dp(8))

        box.addView(label("Số giây chờ app tải xong trước khi chạy macro", 13f, false))
        val etS = EditText(this)
        etS.inputType = InputType.TYPE_CLASS_NUMBER
        etS.setSingleLine()
        etS.setText(app.startupSec.toString())
        box.addView(etS)

        box.addView(label("Số lần chạy macro (1–9999)", 13f, false))
        val etR = EditText(this)
        etR.inputType = InputType.TYPE_CLASS_NUMBER
        etR.setSingleLine()
        etR.setText(app.repeat.toString())
        box.addView(etR)

        box.addView(label("Nghỉ giữa các lần chạy (giây)", 13f, false))
        val etG = EditText(this)
        etG.inputType = InputType.TYPE_CLASS_NUMBER
        etG.setSingleLine()
        etG.setText(app.repeatGapSec.toString())
        box.addView(etG)

        box.addView(label("Macro riêng của app này", 13f, false))
        val etM = EditText(this)
        etM.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        etM.minLines = 8
        etM.gravity = Gravity.TOP or Gravity.START
        etM.hint = "tap,50.0,80.0,3000,60\nswipe,50.0,80.0,50.0,30.0,1000,400"
        etM.setText(app.macro)
        box.addView(etM)

        box.addView(
            label(
                "Cách dễ nhất là ghi bằng nút nổi (⏺). Sửa tay: mỗi dòng một thao tác, x/y là % màn hình:\n" +
                    "tap,x,y,chờ_ms,giữ_ms\n" +
                    "swipe,x1,y1,x2,y2,chờ_ms,thời_gian_vuốt_ms\n" +
                    "Dòng bắt đầu bằng # là ghi chú.",
                12f, false
            )
        )

        val sv = ScrollView(this)
        sv.addView(box)

        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setView(sv)
            .setPositiveButton("Lưu") { _, _ ->
                val sec = (etS.text.toString().toIntOrNull() ?: 25).coerceIn(0, 600)
                val text = etM.text.toString()
                val rep = (etR.text.toString().toIntOrNull() ?: 1).coerceIn(1, 9999)
                val gap = (etG.text.toString().toIntOrNull() ?: 0).coerceIn(0, 3600)
                Prefs.updateApp(this, pkg) {
                    it.copy(startupSec = sec, macro = text, repeat = rep, repeatGapSec = gap)
                }
                renderApps()
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun testApp(pkg: String) {
        val service = WatchdogService.instance
        if (service == null) {
            toast("Dịch vụ trợ năng chưa bật")
        } else if (service.runNow(pkg)) {
            toast("Đang mở lại app rồi chạy macro…")
        } else {
            toast("App này đang bận, thử lại sau")
        }
    }

    private fun confirmRemove(a: AppConfig) {
        AlertDialog.Builder(this)
            .setTitle("Xoá ${a.label}?")
            .setMessage("Macro của app này cũng sẽ bị xoá.")
            .setPositiveButton("Xoá") { _, _ ->
                Prefs.removeApp(this, a.pkg)
                renderApps()
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun showPicker() {
        val pm = packageManager
        val existing = Prefs.getApps(this).map { it.pkg }.toSet()
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(query, 0)
            .map { Pair(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.second != packageName && it.second !in existing }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }

        if (apps.isEmpty()) {
            toast("Không còn app nào để thêm")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Thêm app giám sát")
            .setItems(apps.map { "${it.first}\n${it.second}" }.toTypedArray()) { _, i ->
                Prefs.addApp(this, AppConfig(apps[i].second, apps[i].first))
                renderApps()
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Đã tắt tối ưu pin cho app này")
        } else {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }
    }

    // ---------------- Tiện ích giao diện ----------------

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(s: String, size: Float, bold: Boolean): TextView {
        val tv = TextView(this)
        tv.text = s
        tv.textSize = size
        if (bold) tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
        tv.setPadding(0, dp(10), 0, dp(2))
        return tv
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun rowButton(text: String, onClick: () -> Unit): Button {
        val b = button(text, onClick)
        b.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        return b
    }
}
