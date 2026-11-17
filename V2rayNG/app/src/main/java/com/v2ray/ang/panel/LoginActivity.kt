package com.v2ray.ang.panel

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("panel", MODE_PRIVATE) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        val saved = prefs.getString("token", null)
        val u = EditText(this).apply { hint = "نام کاربری"; setSingleLine() }
        val p = EditText(this).apply { hint = "رمز عبور"; setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val msg = TextView(this).apply { gravity = Gravity.CENTER }
        val btn = Button(this).apply { text = "ورود و دریافت کانفیگ‌ها" }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(60, 160, 60, 60)
            addView(TextView(context).apply { text = "VPN Hub"; textSize = 28f; gravity = Gravity.CENTER })
            addView(u); addView(p); addView(btn); addView(msg)
        })

        fun sync(token: String) = lifecycleScope.launch {
            msg.text = "در حال دریافت کانفیگ‌ها..."
            val r = withContext(Dispatchers.IO) { PanelApi.configs(token) }
            when {
                r.ok && r.links.isNotEmpty() -> {
                    withContext(Dispatchers.IO) { AngConfigManager.importBatchConfig(r.links.joinToString("\n"), "", false) }
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java)); finish()
                }
                r.ok -> msg.text = "هنوز کانفیگی به حساب شما اختصاص داده نشده است"
                r.token == "expired" -> { prefs.edit().remove("token").apply(); msg.text = "نشست تمام شد؛ دوباره وارد شوید" }
                else -> msg.text = r.msg
            }
        }

        btn.setOnClickListener {
            lifecycleScope.launch {
                msg.text = "در حال ورود..."
                val r = withContext(Dispatchers.IO) { PanelApi.login(u.text.toString().trim(), p.text.toString()) }
                if (r.ok) { prefs.edit().putString("token", r.token).apply(); sync(r.token!!) } else msg.text = r.msg
            }
        }
        if (saved != null) sync(saved)
    }
}
