package kr.ac.demo.ai_study_planner_mobile

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import android.util.AtomicFile
import java.io.File
import org.json.JSONObject

class MainActivity : FlutterActivity() {
    companion object { private val requestLock = Any() }
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val file = AtomicFile(File(applicationContext.filesDir, "study-request-keys-v1.json"))
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "ai_study_planner/study_requests").setMethodCallHandler { call, result ->
            try {
                synchronized(requestLock) {
                    val scope = call.argument<String>("scope") ?: error("scope")
                    require(scope.length in 1..512 && !scope.contains('\u0000'))
                    val data = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
                        val bytes = file.readFully()
                        require(bytes.size <= 65536)
                        JSONObject(bytes.toString(Charsets.UTF_8))
                    } else JSONObject()
                    fun write() {
                        val bytes = data.toString().toByteArray(Charsets.UTF_8)
                        require(bytes.size <= 65536)
                        val stream = file.startWrite()
                        try { stream.write(bytes); file.finishWrite(stream) }
                        catch (e: Exception) { file.failWrite(stream); throw e }
                    }
                    val old = if (data.has(scope)) data.getString(scope) else null
                    if (old != null) require(old.matches(Regex("[a-z0-9][a-z0-9_-]{15,79}")))
                    when (call.method) {
                        "pending" -> result.success(old)
                        "reserve" -> {
                            val key = call.argument<String>("key") ?: error("key")
                            require(key.matches(Regex("[a-z0-9][a-z0-9_-]{15,79}")))
                            if (old == null) { data.put(scope, key); write() }
                            result.success(mapOf("key" to (old ?: key), "existing" to (old != null)))
                        }
                        "complete" -> {
                            if (old != null && old == call.argument<String>("key")) { data.remove(scope); write() }
                            result.success(null)
                        }
                        else -> result.notImplemented()
                    }
                }
            } catch (_: Exception) {
                result.error("REQUEST_STORE_FAILED", "요청 키 보관을 확인할 수 없습니다. 전송하지 말고 기록을 확인하세요.", null)
            }
        }
    }
}
