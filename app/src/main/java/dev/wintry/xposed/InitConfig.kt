package dev.wintry.xposed

import com.highcapable.yukihookapi.hook.log.YLog
import dev.wintry.xposed.HookEntry.Companion.wintryDir
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class InitConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val forceUpdate: Boolean = false,
    val skipUpdate: Boolean = false,
    val bundlePath: String? = null,
    val safeMode: Boolean = false,
) {
    companion object {
        const val DEFAULT_BASE_URL = "http://192.168.0.157:4040/" // Change to the real one once it's real
        val Current: InitConfig by lazy {
            try {
                val file = File(wintryDir, "init_config.json")
                Json.decodeFromString<InitConfig>(file.readText())
            } catch (e: Exception) {
                YLog.info("Failed to load InitConfig, using default values. Reason: ${e::class.simpleName}")
                InitConfig()
            }
        }
    }
}
