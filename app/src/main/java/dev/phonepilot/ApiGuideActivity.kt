package dev.phonepilot

import android.app.AlertDialog
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject

class ApiGuideActivity:IosActivity() {
    private lateinit var core:CommandDispatcher
    private lateinit var contract:ApiContract
    private var protocol="http"
    private var action:String?=null
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);core=CommandDispatcher(this);protocol=state?.getString("protocol") ?: intent.getStringExtra("protocol") ?: "http"
        if(protocol !in listOf("http","ws","mcp")) protocol="http"
        action=state?.getString("action") ?: intent.getStringExtra("action");render()
    }
    override fun onSaveInstanceState(out:Bundle) {out.putString("protocol",protocol);out.putString("action",action);super.onSaveInstanceState(out)}
    @Deprecated("Platform navigation") override fun onBackPressed() {if(action!=null){action=null;render()}else finish()}
    private fun render() {
        contract=ApiContract(core,ApiContract.host(this))
        val spec=action?.let {name->core.definitions.firstOrNull {it.name==name}}
        if(spec==null) {action=null;page("${if(protocol=="ws") "WebSocket" else protocol.uppercase()} 接入","设置",{finish()});overview()}
        else {page(spec.title,"功能列表",{action=null;render()});detail(spec)}
    }
    private fun code(title:String,value:String) {
        val text=label(value,12f).apply {typeface=android.graphics.Typeface.MONOSPACE;setTextIsSelectable(true);setPadding(dp(18),dp(14),dp(18),dp(14))}
        AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply {addView(text)}).setPositiveButton("完成",null).setNeutralButton("复制") {_,_->copy(value)}.show()
    }
    private fun overview() {
        val connection=group("连接")
        row(connection,"接口地址",contract.endpoint(protocol),action={copy(contract.endpoint(protocol))})
        row(connection,"认证","Bearer Token",action={code("认证方式","Authorization: Bearer <TOKEN>\nToken 必须存在、启用且未过期。请勿放入 URL。")})
        row(connection,"当前 Token",TokenStore.selected(this)?.name ?: "没有有效 Token")
        row(connection,"复制当前密钥",action={copy(RuntimeState.token(this))})
        gap(16)
        val guide=group("接入与发现")
        row(guide,"接入步骤与错误说明",subtitle="初始化、认证、协议约定、超时与限制",action={code("接入说明",contract.guide(protocol))})
        row(guide,"动态发现请求",subtitle=if(protocol=="mcp") "tools/list" else "list_actions / describe_action",action={code("发现请求",contract.discovery(protocol).toString(2))})
        row(guide,"完整功能目录 JSON","${core.definitions.size} 项",action={code("动作注册表",core.catalog().toString(2))})
        row(guide,"复制完整接入文档",subtitle="全部功能、参数 Schema、请求 / 响应及命令",action={copy(contract.full(protocol))})
        if(protocol=="mcp") row(guide,"MCP 客户端配置",action={
            val token=RuntimeState.token(this);if(token.isEmpty()) {toast("请先新建有效 Token");return@row}
            copy(JSONObject().put("mcpServers",JSONObject().put("phonepilot",JSONObject().put("url",contract.endpoint(protocol)).put("headers",JSONObject().put("Authorization","Bearer $token")))).toString(2))
        })
        footnote("文档和参数直接读取实际动作注册表。响应示例仅展示结构，不代表当前设备数据。")
        gap(16)
        core.definitions.groupBy {it.category}.forEach { (category,values)->
            val list=group(category)
            values.forEach {spec->row(list,spec.title,spec.name,subtitle=if(spec.root) "需要 Root" else if(spec.mutating) "修改设备状态" else "只读",action={action=spec.name;render()})}
            gap(16)
        }
    }
    private fun type(schema:JSONObject):String = schema.opt("type")?.let {if(it is JSONArray) (0 until it.length()).joinToString(" / ") {i->it.getString(i)} else it.toString()} ?: "任意"
    private fun detail(spec:ActionSpec) {
        val overview=group("${spec.name} · v1.0")
        val description=label(spec.description+"\n\n"+spec.notes,14f).apply {setPadding(dp(16),dp(14),dp(16),dp(14));setLineSpacing(dp(4).toFloat(),1f)};overview.addView(description)
        row(overview,"权限",if(spec.root) "需要 Root + 有效 Token" else "有效 Token")
        row(overview,"影响",if(spec.mutating) "会改变设备状态" else "只读")
        gap(16)
        val input=group("请求参数")
        val properties=spec.input.getJSONObject("properties")
        val required=spec.input.optJSONArray("required") ?: JSONArray()
        if(properties.length()==0) row(input,"无业务参数","args: {}")
        properties.keys().forEach {name->
            val schema=properties.getJSONObject(name)
            val limits=buildString {
                if(schema.has("minimum")) append("；最小 ${schema.get("minimum")}")
                if(schema.has("maximum")) append("；最大 ${schema.get("maximum")}")
                if(schema.has("minLength")) append("；最短 ${schema.getInt("minLength")} 字符")
                if(schema.has("maxLength")) append("；最长 ${schema.getInt("maxLength")} 字符")
                if(schema.has("pattern")) append("；格式 ${schema.getString("pattern")}")
            }
            row(input,name,type(schema)+if((0 until required.length()).any {required.getString(it)==name}) " · 必填" else " · 可选",subtitle=schema.optString("description")+limits)
        }
        row(input,"查看 / 复制输入 Schema",action={code("输入 Schema",spec.input.toString(2))})
        gap(16)
        val output=group("成功响应参数")
        val fields=spec.output.optJSONObject("properties")
        if(fields==null) row(output,"结构化 JSON 对象") else fields.keys().forEach {name->val schema=fields.getJSONObject(name);row(output,name,type(schema),subtitle=schema.optString("description"))}
        row(output,"查看 / 复制输出 Schema",action={code("输出 Schema",spec.output.toString(2))})
        gap(16)
        val examples=group("${protocol.uppercase()} 请求与响应")
        row(examples,"请求 JSON",action={code("请求 JSON",contract.request(protocol,spec).toString(2))})
        row(examples,"响应 JSON 示例",subtitle="仅展示结构，非实时返回值",action={code("响应示例",contract.response(protocol,spec).toString(2))})
        row(examples,"复制调用命令",subtitle="包含当前所选 Token",action={val token=RuntimeState.token(this);if(token.isEmpty()) toast("请先新建有效 Token") else copy(contract.command(protocol,spec,token))})
        row(examples,"复制本功能完整说明",subtitle="参数、权限、示例、错误与注意事项",action={copy(contract.detail(protocol,spec))})
        row(examples,"错误与协议约定",action={code("错误与协议约定",contract.guide(protocol))})
        footnote("同一动作在 MCP、HTTP、WebSocket 中使用相同参数及成功结果结构；只有外层协议封装不同。")
    }
}
