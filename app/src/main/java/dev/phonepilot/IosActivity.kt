package dev.phonepilot

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

/** Shared native, compact grouped-list presentation. No web view or simulated status data. */
abstract class IosActivity : Activity() {
    protected var dark = false
    protected var ink = 0; protected var secondary = 0; protected var surface = 0; protected var canvas = 0
    protected var blue = 0; protected var green = 0; protected var red = 0; private var separator = 0
    protected lateinit var content: LinearLayout
    protected fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    protected fun label(value: String, size: Float = 16f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); includeFontPadding = false
        if(bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    protected fun rounded(color: Int, radius: Int = 12) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    protected fun palette() {
        dark = when(getSharedPreferences("pilot",0).getString("theme","system")) { "dark" -> true; "light" -> false; else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES }
        setTheme(if(dark) R.style.PilotDark else R.style.PilotLight)
        ink = Color.parseColor(if(dark) "#F5F5F7" else "#1C1C1E")
        secondary = Color.parseColor(if(dark) "#98989F" else "#8E8E93")
        surface = Color.parseColor(if(dark) "#1C1C1E" else "#FFFFFF")
        canvas = Color.parseColor(if(dark) "#000000" else "#F2F2F7")
        separator = Color.parseColor(if(dark) "#38383A" else "#E5E5EA")
        blue = Color.parseColor(if(dark) "#0A84FF" else "#007AFF")
        green = Color.parseColor("#34C759"); red = Color.parseColor("#FF453A")
        window.statusBarColor = canvas; window.navigationBarColor = canvas
        window.decorView.systemUiVisibility = if(dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }
    protected fun page(title: String, backLabel: String? = null, back: (() -> Unit)? = null, right: String? = null, rightAction: (() -> Unit)? = null, gear: Boolean = false) {
        palette()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(canvas) }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12),0,dp(12),0) }
        bar.addView(label(if(backLabel == null) "PhonePilot" else "‹ $backLabel", if(backLabel == null) 16f else 17f, if(backLabel == null) secondary else blue, backLabel==null).apply {
            gravity = Gravity.CENTER_VERTICAL; minHeight = dp(48); setPadding(dp(8),0,dp(8),0)
            if(back != null) setOnClickListener { back() }
        },LinearLayout.LayoutParams(0,dp(48),1f))
        if(gear) bar.addView(ImageView(this).apply { setImageResource(dev.phonepilot.R.drawable.ic_settings); imageTintList=ColorStateList.valueOf(blue); setPadding(dp(10),dp(10),dp(10),dp(10)); contentDescription="设置"; setOnClickListener { rightAction?.invoke() } },LinearLayout.LayoutParams(dp(48),dp(48)))
        else if(right != null) bar.addView(label(right,17f,blue).apply { gravity=Gravity.CENTER; minWidth=dp(44); minHeight=dp(48); contentDescription=if(right=="＋") "新建 Token" else right; setOnClickListener { rightAction?.invoke() } })
        root.addView(bar)
        val scroll = ScrollView(this).apply { isFillViewport=true; isVerticalScrollBarEnabled=false }
        content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(4),dp(20),dp(28)) }
        scroll.addView(content); root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f)); setContentView(root)
        content.addView(label(title,32f,ink,true),LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(22) })
    }
    protected fun group(title: String? = null): LinearLayout {
        if(title != null) content.addView(label(title,12f,secondary).apply { setPadding(dp(14),0,0,dp(8)) })
        return LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; background=rounded(surface); clipToOutline=true
            content.addView(this,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
        }
    }
    protected fun gap(height: Int = 20) { content.addView(Space(this),LinearLayout.LayoutParams(1,dp(height))) }
    protected fun footnote(value: String) { content.addView(label(value,12f,secondary).apply { setPadding(dp(14),dp(2),dp(12),dp(6)); setLineSpacing(dp(3).toFloat(),1f) }) }
    protected fun row(parent: LinearLayout, title: String, value: String = "", subtitle: String? = null, action: (() -> Unit)? = null, control: View? = null, destructive: Boolean = false): TextView {
        if(parent.childCount > 0) parent.addView(View(this).apply { setBackgroundColor(separator) },LinearLayout.LayoutParams(-1,dp(1).coerceAtLeast(1)).apply { leftMargin=dp(16) })
        val line=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; minimumHeight=dp(50); setPadding(dp(16),dp(12),dp(14),dp(12)) }
        val left=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        left.addView(label(title,16f,if(destructive) red else ink))
        if(subtitle != null) left.addView(label(subtitle,12f,secondary).apply { setPadding(0,dp(5),dp(8),0); setLineSpacing(dp(2).toFloat(),1f) })
        line.addView(left,LinearLayout.LayoutParams(0,-2,1f))
        val detail=label(value,14f,secondary).apply { gravity=Gravity.END; maxWidth=dp(210) }
        if(control != null) line.addView(control) else line.addView(detail)
        if(action != null) { line.addView(label("›",24f,secondary).apply { setPadding(dp(10),0,0,0) }); line.background=RippleDrawable(ColorStateList.valueOf(if(dark) 0x22333333 else 0x11000000),null,null); line.setOnClickListener { action() } }
        parent.addView(line)
        return detail
    }
    protected fun toggle(checked: Boolean, name: String, changed: (Boolean) -> Unit) = Switch(this).apply {
        isChecked=checked; contentDescription=name; showText=false
        thumbTintList=ColorStateList.valueOf(Color.WHITE)
        trackTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(green,if(dark) 0xFF555558.toInt() else 0xFFCCCCD0.toInt()))
        setOnCheckedChangeListener { _, value -> changed(value) }
    }
    protected fun copy(value: String) {
        if(value.isBlank()) { toast("没有有效 Token，请先新建或启用"); return }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PhonePilot",value)); toast("已复制")
    }
    protected fun toast(value: String) { Toast.makeText(this,value,Toast.LENGTH_SHORT).show() }
}
