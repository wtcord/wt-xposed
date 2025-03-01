package dev.wintry.xposed

import com.highcapable.yukihookapi.hook.param.PackageParam
import dev.wintry.xposed.Patches.hookForCallBridge
import dev.wintry.xposed.Patches.hookScriptLoader
import dev.wintry.xposed.modules.BubbleModule
import dev.wintry.xposed.modules.LogBoxModule
import dev.wintry.xposed.modules.UpdaterModule
import dev.wintry.xposed.modules.base.HookModule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

class HookEntry {
    companion object {
        lateinit var packageParam: PackageParam

        val wintryDir: File by lazy {
            packageParam.appContext!!.getExternalFilesDir("wintry")!!.apply { mkdirs() }
        }

        val HookModules = listOf<HookModule>(
            UpdaterModule(),
            BubbleModule(),
            LogBoxModule()
        )
    }

    fun PackageParam.loadWintry() {
        val catalystInstanceImplClass = "com.facebook.react.bridge.CatalystInstanceImpl".toClassOrNull() ?: return

        packageParam = this

        // Get the activity
        RuntimeHelper.setupHook(this)

        hookForCallBridge()
        hookScriptLoader(catalystInstanceImplClass, ::getPayloadString)

        HookModules.forEach { it.onHook(packageParam) }
    }

    private fun getPayloadString(): String = Json.encodeToString(buildJsonObject {
        putJsonObject("loader") {
            put("name", "WintryXposed")
            put("version", BuildConfig.VERSION_NAME)
            put("initConfig", Json { encodeDefaults = true }.encodeToJsonElement(InitConfig.Current))
            putJsonObject("constants") {
                put("WINTRY_DIR", wintryDir.absolutePath)
                put("DEFAULT_BASE_URL", InitConfig.DEFAULT_BASE_URL)
            }
            putJsonObject("modules") {
                for (m in HookModules) {
                    putJsonObject(m.name) {
                        putJsonObject("functions") {
                            val registeredFunction = m.getRegisteredFunctions()
                            for ((name, version) in registeredFunction) {
                                put(name, version)
                            }
                        }
                        put("constants", Json.encodeToJsonElement(m.getConstants()))
                    }
                }
            }
            putJsonObject("preload") {
                for (json in File(wintryDir, "preload").walk()) {
                    if (json.isFile) put(json.name, json.readText())
                }
            }
        }
    })
}