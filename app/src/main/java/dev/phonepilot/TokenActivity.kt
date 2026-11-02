package dev.phonepilot

import android.app.AlertDialog
import android.os.Bundle
import android.widget.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class TokenActivity : IosActivity() {
    private var screen="tokens"
    private var filter:String?=null
    private var offset=0
    private val history=mutableListOf<Triple<String,String?,Int>>()
    private val worker=Executors.newSingleThreadExecutor()
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);AuditStore.init(this)
        screen=state?.getString("screen") ?: intent.getStringExtra("page") ?: "tokens"
        filter=state?.getString("filter");offset=state?.getInt("offset") ?: 0
        state?.getString("history")?.let { saved -> val values=org.json.JSONArray(saved);for(i in 0 until values.length()) { val j=values.getJSONObject(i); history.add(Triple(j.getString("screen"),j.optString("filter").ifEmpty { null },j.getInt("offset"))) } }
        render()
    }
    override fun onSaveInstanceState(out:Bundle) { out.putString("screen",screen);out.putString("filter",filter);out.putInt("offset",offset);out.putString("history",org.json.JSONArray(history.map { JSONObject().put("screen",it.first).put("filter",it.second ?: "").put("offset",it.third) }).toString());super.onSaveInstanceState(out) }
    override fun onDestroy() { worker.shutdownNow();super.onDestroy() }
    @Deprecated("Platform navigation") override fun onBackPressed() { back() }
    private fun date(value:Long,short:Boolean=false)=if(value==0L) "长期有效" else SimpleDateFormat(if(short) "yyyy/MM/dd" else "yyyy-MM-dd HH:mm:ss",Locale.getDefault()).format(Date(value))
    private fun back() {
        if(history.isEmpty()) finish() else { val previous=history.removeAt(history.lastIndex);screen=previous.first;filter=previous.second;offset=previous.third;render() }
    }
    private fun go(next:String,id:String?=null) { history.add(Triple(screen,filter,offset));screen=next;filter=id;offset=0;render() }
    private fun render() {
        when(screen) {
            "storage"->{page("日志存储","设置",{back()});storage()}
            "logs"->{page("调用统计",if(history.isEmpty()) "设置" else "返回",{back()},"清空",{clearLogs()});logs()}
            "token"->{page("Token 详情","Token",{back()});tokenDetail()}
            else->{page("Token","设置",{back()},"＋",{editToken(null)});tokens()}
        }
    }
    private fun tokens() {
        val values=TokenStore.all(this)
        val group=group("${values.count { it.valid() }} 个有效 · ${values.size} 个 Token")
        values.forEach { token ->
            val s=AuditStore.stats(this,token.id)
            val state=when { !token.enabled->"已停用";!token.valid()->"已过期";else->if(token.expires==0L) "长期有效" else "${date(token.expires,true)} 到期" }
            row(group,token.name,subtitle="$state · ${s.getLong("count")} 次调用",action={go("token",token.id)},control=toggle(token.enabled,"启用 ${token.name}") { checked->TokenStore.enable(this,token.id,checked);render() })
        }
        footnote("新建默认有效期 365 天，也可设为长期有效。")
    }
    private fun tokenDetail() {
        val token=TokenStore.all(this).firstOrNull { it.id==filter } ?: run { back();return }
        val g=group("访问凭证")
        row(g,token.name,control=toggle(token.enabled,"启用 ${token.name}") { checked->TokenStore.enable(this,token.id,checked);render() })
        row(g,"名称与有效期",if(!token.valid() && token.enabled) "已过期" else date(token.expires,true),action={editToken(token)})
        row(g,"复制密钥",action={copy(token.secret)})
        row(g,"用于调用示例",if(TokenStore.selected(this)?.id==token.id) "已选择" else "",action={if(token.valid()) {TokenStore.select(this,token.id);render()} else toast("此 Token 已停用或过期")})
        footnote("创建于 ${date(token.created)} · ${token.id.take(8)}")
        gap(16)
        val stats=AuditStore.stats(this,token.id);summary(stats)
        row(group(),"调用明细",action={go("logs",token.id)})
    }
    private fun summary(stats:JSONObject) {
        val count=stats.getLong("count");val successful=stats.getLong("success")
        val group=group("调用概况")
        val line=LinearLayout(this).apply { setPadding(dp(12),dp(18),dp(12),dp(18)) }
        listOf("调用次数" to "$count","成功率" to if(count==0L) "—" else "${successful*100/count}%","平均耗时" to "${stats.getLong("avgMs")} ms").forEach { (title,value)->
            line.addView(LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;gravity=android.view.Gravity.CENTER;addView(label(value,22f,ink,true));addView(label(title,11f,secondary).apply { setPadding(0,dp(7),0,0) }) },LinearLayout.LayoutParams(0,-2,1f))
        };group.addView(line)
        footnote("成功 $successful 次 · 失败 ${count-successful} 次。统计随保留日志更新。")
        gap(14)
    }
    private fun storage() {
        val record=group("记录")
        val choices=LinearLayout(this).apply { setPadding(dp(4),dp(4),dp(4),dp(4));background=rounded(if(dark) 0xFF2C2C2E.toInt() else 0xFFE3E3E8.toInt(),10) }
        listOf(true to "开启日志",false to "关闭日志").forEach { (enabled,title)->
            choices.addView(label(title,14f,if(AuditStore.enabled(this)==enabled) ink else secondary,true).apply {
                gravity=android.view.Gravity.CENTER;minHeight=dp(36)
                if(AuditStore.enabled(this@TokenActivity)==enabled) background=rounded(surface,8)
                setOnClickListener { AuditStore.setEnabled(this@TokenActivity,enabled);render() }
            },LinearLayout.LayoutParams(0,dp(36),1f))
        };record.addView(choices)
        footnote(if(AuditStore.enabled(this)) "记录请求与响应，密钥自动脱敏。" else "新调用不记录，已有日志仍可查看。")
        gap(16)
        val retention=group("保留策略")
        row(retention,"保留时间","${AuditStore.retentionDays(this)} 天",action={editRetention()})
        row(retention,"自动清理",subtitle="删除 ${AuditStore.retentionDays(this)} 天以前的数据",control=toggle(AuditStore.autoCleanup(this),"自动清理过期日志") { automatic->saveRetention(AuditStore.retentionDays(this),automatic) })
        footnote(if(AuditStore.autoCleanup(this)) "服务运行时每小时检查；启动时也会清理。" else "已停止自动清理，历史记录保留至手动删除。")
        gap(16)
        val engine=group("存储")
        row(engine,"日志引擎","腾讯 XLog")
        row(engine,"状态",if(AuditStore.error.isEmpty()) "正常" else "异常")
        row(engine,"已保留日志","${AuditStore.stats(this).getLong("count")} 条")
        if(AuditStore.error.isNotEmpty()) footnote(AuditStore.error)
        gap(16)
        row(group(),"清空所有日志",action={confirmDelete("清空全部请求和响应日志？") {AuditStore.delete(this)}},destructive=true)
        footnote("删除会同步更新调用统计。日志仅保存在本机。")
    }
    private fun editRetention() {
        val input=EditText(this).apply { inputType=android.text.InputType.TYPE_CLASS_NUMBER;setText(AuditStore.retentionDays(this@TokenActivity).toString());setSelectAllOnFocus(true) }
        val form=LinearLayout(this).apply { setPadding(dp(22),dp(8),dp(22),0);addView(input,LinearLayout.LayoutParams(-1,-2)) }
        val dialog=AlertDialog.Builder(this).setTitle("保留天数").setMessage("默认 30 天，可设置 1–36500 天。").setView(form).setNegativeButton("取消",null).setPositiveButton("保存",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val days=input.text.toString().toIntOrNull()
            if(days==null || days !in 1..36500) toast("请输入 1 至 36500 天") else { dialog.dismiss();saveRetention(days,AuditStore.autoCleanup(this)) }
        } };dialog.show()
    }
    private fun saveRetention(days:Int,automatic:Boolean) {
        worker.execute { try { AuditStore.setRetention(this,days,automatic);runOnUiThread {if(!isDestroyed) render()} } catch (_:Exception) {runOnUiThread {if(!isDestroyed) toast("保存失败，请重试")}} }
    }
    private fun logs() {
        summary(AuditStore.stats(this,filter))
        val picker=group()
        row(picker,"Token",filter?.let { id->TokenStore.all(this).firstOrNull {it.id==id}?.name } ?: "全部",action={
            val tokens=TokenStore.all(this)
            AlertDialog.Builder(this).setTitle("按 Token 筛选").setItems((listOf("全部 Token")+tokens.map {it.name}).toTypedArray()) { _,index->filter=if(index==0) null else tokens[index-1].id;offset=0;render() }.setNegativeButton("取消",null).show()
        })
        gap(16)
        val entries=AuditStore.list(this,filter,offset)
        val list=group("最近调用")
        entries.forEach { log->
            val success=log.getInt("success")==1
            row(list,log.getString("action"),SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date(log.getLong("time"))),
                "${log.getString("transport")} · ${log.getString("token_name")}\n${if(success) "成功" else "失败"} ${log.getInt("status")} · ${log.getLong("duration")} ms · ${date(log.getLong("time"),true)}",action={detail(log)})
        }
        if(entries.isEmpty()) footnote("暂无调用记录。")
        gap(14)
        val count=AuditStore.stats(this,filter).getLong("count")
        if(offset>0 || offset+entries.size<count) { val paging=group();if(offset>0) row(paging,"上一页",action={offset=(offset-30).coerceAtLeast(0);render()});if(offset+entries.size<count) row(paging,"下一页",action={offset+=30;render()}) }
        footnote("第 ${offset/30+1} 页 · 每页 30 条")
    }
    private fun clearLogs() { val id=filter;confirmDelete("删除当前范围的全部日志？") {AuditStore.delete(this,tokenId=id)} }
    private fun detail(log:JSONObject) {
        val body="时间：${date(log.getLong("time"))}\nToken：${log.getString("token_name")}\n入口：${log.getString("transport")}\n来源：${log.getString("peer")}\n状态：${log.getInt("status")} · ${log.getLong("duration")} ms\n\n请求\n${log.getString("request")}\n\n响应\n${log.getString("response")}"
        val view=label(body,13f).apply { typeface=android.graphics.Typeface.MONOSPACE;setTextIsSelectable(true);setPadding(dp(20),dp(12),dp(20),dp(12)) }
        AlertDialog.Builder(this).setTitle("调用明细").setView(ScrollView(this).apply {addView(view)}).setPositiveButton("完成",null).setNeutralButton("复制") { _,_->copy(body) }.setNegativeButton("删除") { _,_->confirmDelete("删除此条日志？") {AuditStore.delete(this,id=log.getString("id"))} }.show()
    }
    private fun confirmDelete(message:String,task:()->Unit) {
        AlertDialog.Builder(this).setTitle("删除日志").setMessage(message).setNegativeButton("取消",null).setPositiveButton("删除") { _,_->worker.execute {try {task();runOnUiThread {if(!isDestroyed) {offset=0;render();toast(if(AuditStore.error.isEmpty()) "已删除" else AuditStore.error)}}} catch (_:Exception) {runOnUiThread {if(!isDestroyed) toast("删除失败，请重试")}}} }.show()
    }
    private fun editToken(token: ApiToken?) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val name = EditText(this).apply { hint = "Token 名称"; setText(token?.name ?: "外部调用") }; form.addView(name)
        form.addView(label("有效期从保存时间起算；默认 365 天。", 13f, secondary))
        val days = EditText(this).apply { hint = "有效天数（1–36500）"; inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText(if(token != null && token.expires > System.currentTimeMillis()) ((token.expires-System.currentTimeMillis()+86399999L)/86400000L).toString() else "365") }; form.addView(days)
        val forever = CheckBox(this).apply { text = "长期有效（最大）"; isChecked = token?.expires == 0L; setOnCheckedChangeListener { _, checked -> days.isEnabled = !checked } }; form.addView(forever); days.isEnabled = !forever.isChecked
        val dialog = AlertDialog.Builder(this).setTitle(if(token==null) "新建 Token" else "编辑 Token").setView(form).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val validity = if(forever.isChecked) null else days.text.toString().toLongOrNull() ?: error("请输入有效天数")
                if(token==null) TokenStore.create(this, name.text.toString(), validity) else TokenStore.edit(this, token.id, name.text.toString(), validity)
                dialog.dismiss(); render()
            } catch (e: Exception) { Toast.makeText(this, e.message ?: "请输入名称和有效天数", Toast.LENGTH_LONG).show() }
        } }; dialog.show()
    }

}
