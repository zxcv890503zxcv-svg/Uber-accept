package com.uberauto

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        createChannel(this)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        status = TextView(this).apply { textSize = 16f }
        col.addView(status)

        col.addView(Button(this).apply {
            text = "開啟無障礙設定（啟用「Uber 好單提醒服務」）"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        col.addView(Switch(this).apply {
            text = "啟用提醒"
            isChecked = prefs.enabled
            setOnCheckedChangeListener { _, v -> prefs.enabled = v }
        })

        col.addView(label("每公里至少多少元才提醒："))
        val minEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(prefs.minPerKm.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() })
        }
        col.addView(minEdit)

        col.addView(Switch(this).apply {
            text = "公里數包含「去接客」的距離"
            isChecked = prefs.includePickup
            setOnCheckedChangeListener { _, v -> prefs.includePickup = v }
        })

        col.addView(Button(this).apply {
            text = "儲存"
            setOnClickListener {
                val v = minEdit.text.toString().toFloatOrNull()
                if (v == null || v <= 0) toast("請輸入正確數字")
                else { prefs.minPerKm = v; toast("已儲存：每公里 ≥ $v 元提醒") }
            }
        })

        col.addView(Button(this).apply {
            text = "測試通知"
            setOnClickListener {
                prefs.addLog("（測試）")
                val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.notify(1, android.app.Notification.Builder(this@MainActivity, UberAccessibilityService.CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("💰 好單！要接嗎？")
                    .setContentText("NT$300 / 10.0 公里 = 30.0 元/公里（測試）")
                    .setAutoCancel(true).build())
            }
        })

        // 解析測試：貼上一段文字看能不能讀出價格距離
        col.addView(label("解析測試（貼上派單畫面的文字）："))
        val testEdit = EditText(this).apply {
            hint = "例：NT\$185\n3 分鐘 (1.2 公里)\n15 分鐘 (8.3 公里)\n接受"
            minLines = 3
        }
        col.addView(testEdit)
        col.addView(Button(this).apply {
            text = "測試解析"
            setOnClickListener {
                val r = OfferParser.parse(testEdit.text.toString().lines())
                val d = DecisionMaker.decide(r, prefs.minPerKm.toDouble(), prefs.includePickup)
                toast(if (d.notify) "🔔 會提醒：${d.message}" else d.message)
            }
        })

        col.addView(Button(this).apply {
            text = "看最後一張派單讀到的文字（除錯用）"
            setOnClickListener {
                logView.text = prefs.lastScreenText.ifBlank { "還沒讀到派單畫面" }
            }
        })

        col.addView(label("紀錄："))
        col.addView(Button(this).apply {
            text = "清除紀錄"
            setOnClickListener { prefs.clearLog(); refresh() }
        })
        logView = TextView(this).apply { setTextIsSelectable(true) }
        col.addView(logView)

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val on = isServiceEnabled()
        status.text = if (on) "✅ 服務已啟用，打開 Uber Driver 上線即可"
                      else "⚠️ 服務未啟用，請按下方按鈕到無障礙設定開啟"
        logView.text = prefs.log.ifBlank { "（尚無紀錄）" }
    }

    private fun isServiceEnabled(): Boolean {
        val cn = ComponentName(this, UberAccessibilityService::class.java).flattenToString()
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return list.split(':').any { it.equals(cn, ignoreCase = true) }
    }

    private fun label(s: String) = TextView(this).apply {
        text = s
        setPadding(0, 24, 0, 0)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
