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
/** All transports and future BLE/ESP32 adapters call this boundary. */
class CommandDispatcher(private val context:Context) {
 private val system=SystemController(); private val apps=AppController(system); private val screen=ScreenController(system); private val audio=AudioController(context); private val status=StatusController(audio,PowerController(context))
 val actions=listOf("open_app","open_grok","open_chatgpt","open_doubao","screen_on","screen_off","volume_up","volume_down","volume_set","get_status","root_shell")
 fun execute(action:String,args:JSONObject):JSONObject = when(action) {
 "open_app"->apps.open(args.getString("package"))
 "open_grok"->apps.open("ai.x.grok")
 "open_chatgpt"->apps.open("com.openai.chatgpt")
 "open_doubao"->apps.open("com.larus.nova")
 "screen_on"->screen.set(true); "screen_off"->screen.set(false)
 "volume_up"->audio.change(1); "volume_down"->audio.change(-1)
 "volume_set"->audio.set(args.getInt("value")); "get_status"->status.get().also { result -> val live=DeviceStatus.read(context); live.keys().forEach { key -> result.put(key,live.get(key)) } }
 "root_shell"->system.shell(args.getString("command"))
 else->error("Unknown action: $action")
 }
}
