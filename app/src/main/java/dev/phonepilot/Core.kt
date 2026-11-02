package dev.phonepilot
import android.content.Context
import android.media.AudioManager
import android.os.PowerManager
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SystemController {
 @Synchronized fun shell(command:String):JSONObject {
  val f=java.io.File.createTempFile("pilot", ".log")
  try { val p=ProcessBuilder("su","-c",command).redirectErrorStream(true).redirectOutput(f).start()
   if(!p.waitFor(10,TimeUnit.SECONDS)){ p.destroyForcibly(); error("Root command timed out") }
   return JSONObject().put("exitCode",p.exitValue()).put("output",f.inputStream().use { val buffer=ByteArray(65536); val count=it.read(buffer); String(buffer,0,count.coerceAtLeast(0)) })
  } finally { f.delete() }
 }
}
class AppController(private val system:SystemController) {
 fun open(pkg:String):JSONObject { require(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+").matches(pkg)); return system.shell("monkey -p $pkg -c android.intent.category.LAUNCHER 1") }
}
class ScreenController(private val system:SystemController) { fun set(on:Boolean)=system.shell("input keyevent "+if(on)"224" else "223") }
class AudioController(context:Context) {
 private val audio=context.getSystemService(AudioManager::class.java)
 fun change(direction:Int):JSONObject { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,direction,0); return status() }
 fun set(value:Int):JSONObject { require(value in 0..100); audio.setStreamVolume(3,(audio.getStreamMaxVolume(3)*value/100.0).toInt(),0); return status() }
 fun status()=JSONObject().put("volume",audio.getStreamVolume(3)).put("volumeMax",audio.getStreamMaxVolume(3))
}
class PowerController(private val context:Context) { fun interactive()=context.getSystemService(PowerManager::class.java).isInteractive }
class StatusController(private val audio:AudioController,private val power:PowerController) { fun get()=audio.status().put("screenOn",power.interactive()).put("model",android.os.Build.MODEL) }
/** Register metadata and executor together. UI, discovery and transports read this registry. */
class CommandDispatcher(private val context:Context) {
 private val system=SystemController(); private val apps=AppController(system); private val screen=ScreenController(system)
 private val audio=AudioController(context); private val status=StatusController(audio,PowerController(context))
 private data class Registered(val spec:ActionSpec,val run:(JSONObject)->JSONObject)
 private val registry=linkedMapOf<String,Registered>()
 val definitions:List<ActionSpec> get()=registry.values.map {it.spec}
 val actions:List<String> get()=registry.keys.toList()
 fun catalog()=JSONObject().put("schemaVersion","1.0").put("actions",org.json.JSONArray(definitions.map {it.json()}))
 fun describe(name:String)=registry[name]?.spec ?: throw ActionFailure("UNKNOWN_ACTION","未实现的动作：$name")
 private fun add(spec:ActionSpec,run:(JSONObject)->JSONObject) { require(!registry.containsKey(spec.name)); ContractSchema.validate(spec.exampleArgs,spec.input); ContractSchema.validate(spec.exampleResult,spec.output,"result");registry[spec.name]=Registered(spec,run) }
 init {
  val empty=ContractSchema.obj()
  val shellOutput=ContractSchema.obj("exitCode" to ContractSchema.field("integer","系统命令退出码；0 表示成功，非 0 应按失败处理"),"output" to ContractSchema.field("string","合并的标准输出与错误输出，最多读取 65536 字节，按 UTF-8 解码"))
  val shellExample=JSONObject().put("exitCode",0).put("output","")
  fun root(name:String,title:String,category:String,description:String,input:JSONObject=empty,args:JSONObject=JSONObject(),notes:String,run:(JSONObject)->JSONObject) {
   add(ActionSpec(name,title,category,description,true,true,input,shellOutput,args,JSONObject(shellExample.toString()).apply { if(name=="root_shell") put("output","uid=0(root) gid=0(root)\n") },notes),run)
  }
  root("open_app","打开应用","应用","按 Android 包名启动已安装应用。",ContractSchema.obj("package" to ContractSchema.field("string","Android 应用包名，例如 com.openai.chatgpt").put("pattern","^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")),JSONObject().put("package","com.openai.chatgpt"),"通过 su -c monkey 启动；应用未安装或 Root 不可用时失败。") {apps.open(it.getString("package"))}
  listOf(Triple("open_grok","打开 Grok","ai.x.grok"),Triple("open_chatgpt","打开 ChatGPT","com.openai.chatgpt"),Triple("open_doubao","打开豆包","com.larus.nova")).forEach { (name,title,pkg)->
   root(name,title,"应用","启动 $pkg 对应的应用。",notes="应用别名采用常见发行版包名；包名不同请使用 open_app。") {apps.open(pkg)}
  }
  root("screen_on","唤醒屏幕","屏幕","发送明确的唤醒按键事件。",notes="只唤醒屏幕，不解锁密码或绕过锁屏。") {screen.set(true)}
  root("screen_off","关闭屏幕","屏幕","发送明确的休眠按键事件。",notes="执行后手机屏幕关闭；后台服务是否存活取决于设备策略。") {screen.set(false)}
  val volumeOutput=ContractSchema.obj("volume" to ContractSchema.field("integer","当前媒体音量档位，范围 0..volumeMax"),"volumeMax" to ContractSchema.field("integer","设备媒体音量最大档位"))
  val volumeExample=JSONObject().put("volume",7).put("volumeMax",15)
  listOf(Triple("volume_up","调高音量",1),Triple("volume_down","调低音量",-1)).forEach { (name,title,direction)->
   add(ActionSpec(name,title,"音量","将媒体音量调整一个系统档位。",false,true,empty,volumeOutput,JSONObject(),volumeExample,"只影响媒体音量；返回的是设备档位，而非百分比。")) {audio.change(direction)}
  }
  add(ActionSpec("volume_set","设置音量","音量","按百分比设置媒体音量。",false,true,
   ContractSchema.obj("value" to ContractSchema.field("integer","目标媒体音量百分比，整数 0–100").put("minimum",0).put("maximum",100)),volumeOutput,JSONObject().put("value",50),volumeExample,"value 按设备最大档位转换并向下取整；返回实际档位。")) {audio.set(it.getInt("value"))}
  val statusFields=linkedMapOf<String,JSONObject>()
  fun field(name:String,type:String,description:String,nullable:Boolean=false) {statusFields[name]=ContractSchema.field(type,description,nullable)}
  field("volume","integer","当前媒体音量档位");field("volumeMax","integer","媒体音量最大档位");field("screenOn","boolean","屏幕是否处于交互状态");field("model","string","Android 设备型号")
  field("serviceRunning","boolean","前台控制服务是否运行");field("httpRunning","boolean","HTTP/MCP 端口是否在线");field("webSocketRunning","boolean","WebSocket 端口是否在线")
  field("webSocketClients","integer","当前通过认证的 WebSocket 连接数");field("serviceUptimeSeconds","integer","服务运行时长，单位秒")
  field("appCpuPercent","number","应用 CPU 单核基准百分比；首次采样为 null",true);field("appMemoryMb","integer","应用 PSS 内存，单位 MB")
  field("systemMemoryUsedMb","integer","系统已使用内存，单位 MB");field("systemMemoryTotalMb","integer","系统总内存，单位 MB");field("lowMemory","boolean","系统是否报告内存紧张")
  field("batteryPercent","integer","电池百分比，-1 代表不可用");field("charging","boolean","设备是否接入充电电源");field("temperatureC","number","设备报告电池温度，摄氏度")
  field("batteryCurrentMa","integer","设备报告电池电流，mA；不可用为 null",true);field("network","string","当前网络类型；部分蜂窝识别受权限限制");field("signal","string","信号展示值，可能为不可用提示")
  statusFields["addresses"]=ContractSchema.field("array","本机网络接口 IPv4 地址列表；不是移动网络公网出口查询结果").put("items",ContractSchema.obj("interface" to ContractSchema.field("string","接口名，如 wlan0"),"ip" to ContractSchema.field("string","接口 IPv4 地址"),"private" to ContractSchema.field("boolean","是否为私有地址")))
  val statusSchema=ContractSchema.obj(*statusFields.toList().toTypedArray())
  val statusExample=JSONObject("""{"volume":7,"volumeMax":15,"screenOn":true,"model":"Example Android","serviceRunning":true,"httpRunning":true,"webSocketRunning":true,"webSocketClients":1,"serviceUptimeSeconds":120,"appCpuPercent":1.2,"appMemoryMb":48,"systemMemoryUsedMb":1800,"systemMemoryTotalMb":8000,"lowMemory":false,"batteryPercent":80,"charging":false,"temperatureC":30.5,"batteryCurrentMa":-200,"network":"Wi-Fi","signal":"-55 dBm","addresses":[{"interface":"wlan0","ip":"192.168.1.20","private":true}]}""")
  add(ActionSpec("get_status","获取实时状态","状态","读取屏幕、音量、服务、负载、电池及网络状态。",false,false,empty,statusSchema,JSONObject(),statusExample,"这是调用时的设备状态。文档示例值仅展示结构，不代表真实设备；部分数据受 ROM 或权限限制。")) {
   status.get().also {result->val live=DeviceStatus.read(context);live.keys().forEach {key->result.put(key,live.get(key))}}
  }
  root("root_shell","执行 Root Shell","系统","通过 su -c 执行调用方提供的系统命令。",ContractSchema.obj("command" to ContractSchema.field("string","需要执行的 shell 命令；由可信调用方提供").put("minLength",1).put("maxLength",16384)),JSONObject().put("command","id"),"需要 Root 授权；命令可能修改系统和文件。等待上限 10 秒；超时终止 su 进程不保证已派生的子进程停止。退出码非 0 不能视为成功。") {system.shell(it.getString("command"))}
  val definitionSchema=ContractSchema.obj(
   "name" to ContractSchema.field("string","唯一动作名，也是 HTTP/WS action 与 MCP tool name"),
   "title" to ContractSchema.field("string","功能显示名称"),"category" to ContractSchema.field("string","功能分类"),"version" to ContractSchema.field("string","本动作约定版本"),
   "description" to ContractSchema.field("string","用途说明"),"requiresRoot" to ContractSchema.field("boolean","是否需要 Root 授权"),"mutating" to ContractSchema.field("boolean","是否修改设备状态"),
   "inputSchema" to ContractSchema.field("object","完整输入 JSON Schema"),"outputSchema" to ContractSchema.field("object","完整成功结果 JSON Schema"),
   "exampleArguments" to ContractSchema.field("object","业务参数示例"),"exampleResult" to ContractSchema.field("object","成功结果结构示例，不是实时数据"),
   "exampleIsLive" to ContractSchema.field("boolean","固定 false，标明是示例"),"notes" to ContractSchema.field("string","权限、行为和限制说明"),
   "errors" to ContractSchema.field("array","动作错误码").put("items",ContractSchema.field("string","错误码")))
  add(ActionSpec("list_actions","列举可用动作","接口发现","动态列举当前注册的功能、输入输出 Schema、示例和注意事项。",false,false,empty,
   ContractSchema.obj("schemaVersion" to ContractSchema.field("string","动作目录格式版本"),"actions" to ContractSchema.field("array","当前已注册动作定义").put("items",definitionSchema)),JSONObject(),JSONObject().put("schemaVersion","1.0").put("actions",org.json.JSONArray().put(describe("get_status").json())),"所有协议使用同一注册表；后续新动作注册后自动出现在目录和文档。")) {catalog()}
  add(ActionSpec("describe_action","查看动作定义","接口发现","返回指定动作的完整调用约定。",false,false,
   ContractSchema.obj("name" to ContractSchema.field("string","动作名称，如 get_status").put("minLength",1)),definitionSchema,JSONObject().put("name","get_status"),describe("get_status").json(),"通过 HTTP、WS 或 MCP 调用该动作，均返回相同定义。")) {describe(it.getString("name")).json()}
 }
 fun execute(action:String,args:JSONObject):JSONObject {
  val entry=registry[action] ?: throw ActionFailure("UNKNOWN_ACTION","未实现的动作：$action")
  ContractSchema.validate(args,entry.spec.input)
  val result=entry.run(args)
  try {ContractSchema.validate(result,entry.spec.output,"result")} catch(failure:ActionFailure) {throw ActionFailure("OUTPUT_CONTRACT_VIOLATION",failure.message)}
  return result
 }
}
