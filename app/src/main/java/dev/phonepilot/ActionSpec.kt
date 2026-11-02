package dev.phonepilot

import org.json.JSONArray
import org.json.JSONObject

class ActionFailure(val code:String, override val message:String): IllegalArgumentException(message)

data class ActionSpec(val name:String,val title:String,val category:String,val description:String,val root:Boolean,val mutating:Boolean,
    val input:JSONObject,val output:JSONObject,val exampleArgs:JSONObject,val exampleResult:JSONObject,val notes:String) {
    init { require(Regex("[a-z][a-z0-9_]*").matches(name));require(title.isNotBlank() && category.isNotBlank() && description.isNotBlank() && notes.isNotBlank());require(input.optString("type")=="object" && output.optString("type")=="object") }
    fun json()=JSONObject().put("name",name).put("title",title).put("category",category).put("version","1.0")
        .put("description",description).put("requiresRoot",root).put("mutating",mutating)
        .put("inputSchema",JSONObject(input.toString())).put("outputSchema",JSONObject(output.toString()))
        .put("exampleArguments",JSONObject(exampleArgs.toString())).put("exampleResult",JSONObject(exampleResult.toString())).put("exampleIsLive",false)
        .put("notes",notes).put("errors",JSONArray(listOf("INVALID_ARGUMENT","UNKNOWN_ACTION","EXECUTION_FAILED","OUTPUT_CONTRACT_VIOLATION")))
    fun mcp(modern:Boolean)=JSONObject().put("name",name).put("description","$description${if(root) " 需要 Root 授权。" else ""}")
        .put("inputSchema",JSONObject(input.toString())).put("annotations",JSONObject().put("readOnlyHint",!mutating).put("destructiveHint",name=="root_shell").put("openWorldHint",root))
        .also { if(modern) it.put("title",title).put("outputSchema",JSONObject(output.toString())) }
}

/** JSON Schema subset used by all registered actions. Rejects coercion and undeclared parameters. */
object ContractSchema {
    fun field(type:String,description:String,nullable:Boolean=false)=JSONObject().put("type",if(nullable) JSONArray(listOf(type,"null")) else type).put("description",description)
    fun obj(vararg fields:Pair<String,JSONObject>,required:List<String> = fields.map {it.first},extras:Boolean=false)=JSONObject().put("type","object")
        .put("properties",JSONObject().also { j->fields.forEach {j.put(it.first,it.second)} }).put("required",JSONArray(required)).put("additionalProperties",extras)
    fun validate(value:Any?,schema:JSONObject,path:String="args") {
        val types=schema.opt("type")?.let { if(it is JSONArray) (0 until it.length()).map { i->it.getString(i) } else listOf(it.toString()) } ?: return
        val matches=types.any { type->when(type) {
            "null"->value==null || value==JSONObject.NULL
            "object"->value is JSONObject
            "array"->value is JSONArray
            "string"->value is String
            "integer"->value is Number && value.toDouble().isFinite() && value.toDouble() == kotlin.math.floor(value.toDouble())
            "number"->value is Number && value.toDouble().isFinite()
            "boolean"->value is Boolean
            else->false
        } }
        if(!matches) throw ActionFailure("INVALID_ARGUMENT","$path 必须为 ${types.joinToString(" / ")}")
        if(value==null || value==JSONObject.NULL) return
        if(value is Number) {
            if(schema.has("minimum") && value.toDouble()<schema.getDouble("minimum")) throw ActionFailure("INVALID_ARGUMENT","$path 不得小于 ${schema.get("minimum")}")
            if(schema.has("maximum") && value.toDouble()>schema.getDouble("maximum")) throw ActionFailure("INVALID_ARGUMENT","$path 不得大于 ${schema.get("maximum")}")
        }
        if(value is String) {
            if(value.length<schema.optInt("minLength",0) || value.length>schema.optInt("maxLength",Int.MAX_VALUE)) throw ActionFailure("INVALID_ARGUMENT","$path 长度不符合约定")
            if(schema.has("pattern") && !Regex(schema.getString("pattern")).matches(value)) throw ActionFailure("INVALID_ARGUMENT","$path 格式不正确")
        }
        if(value is JSONObject) {
            val props=schema.optJSONObject("properties") ?: JSONObject()
            val required=schema.optJSONArray("required") ?: JSONArray()
            for(i in 0 until required.length()) if(!value.has(required.getString(i))) throw ActionFailure("INVALID_ARGUMENT","$path 缺少必填参数 ${required.getString(i)}")
            value.keys().forEach { key->
                if(props.has(key)) validate(value.get(key),props.getJSONObject(key),"$path.$key")
                else if(!schema.optBoolean("additionalProperties",true)) throw ActionFailure("INVALID_ARGUMENT","$path 包含未定义参数 $key")
            }
        }
        if(value is JSONArray && schema.has("items")) for(i in 0 until value.length()) validate(value.get(i),schema.getJSONObject("items"),"$path[$i]")
    }
}
