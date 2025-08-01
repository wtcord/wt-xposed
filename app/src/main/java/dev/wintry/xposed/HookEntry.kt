package dev.wintry.xposed

import com.highcapable.yukihookapi.hook.param.PackageParam
import dev.wintry.xposed.Patches.hookImageQueryCache
import dev.wintry.xposed.Patches.hookPackageResourcesIdentifier
import dev.wintry.xposed.Patches.hookScriptLoader
import dev.wintry.xposed.modules.BubbleModule
import dev.wintry.xposed.modules.FsModule
import dev.wintry.xposed.modules.LogBoxModule
import dev.wintry.xposed.modules.UpdaterModule
import dev.wintry.xposed.modules.base.HookModule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
            LogBoxModule(),
            FsModule()
        )
    }

    private fun getPayloadString(): String {
        val json = Json { encodeDefaults = true }

        val encodedInitConfig = json.encodeToJsonElement(InitConfig.Current)

        val moduleSnapshots = HookModules.associate { module ->
            val functions = module.registeredFunctions
            val constants = module.getConstants()
            module.name to (functions to constants)
        }

        val modulesMap = moduleSnapshots.mapValues { (_, pair) ->
            val (functions, constants) = pair
            buildJsonObject {
                putJsonObject("functions") {
                    for ((name, version) in functions) {
                        put(name, version)
                    }
                }
                put("constants", json.encodeToJsonElement(constants))
            }
        }

        val finalJson = buildJsonObject {
            putJsonObject("loader") {
                put("name", "WintryXposed")
                put("version", BuildConfig.VERSION_NAME)
                put("initConfig", encodedInitConfig)
                putJsonObject("constants") {
                    put("WINTRY_DIR", wintryDir.absolutePath)
                    put("DEFAULT_BASE_URL", InitConfig.DEFAULT_BASE_URL)
                }
                put("modules", JsonObject(modulesMap))
            }
        }

        return json.encodeToString(finalJson)
    }

    fun PackageParam.loadWintry() {
        val catalystInstanceImplClass = "com.facebook.react.bridge.CatalystInstanceImpl".toClassOrNull() ?: return

        packageParam = this

        // Get the activity
        RuntimeHelper.setupHook(this)

        hookPackageResourcesIdentifier()
        hookImageQueryCache() // For bridging
        hookScriptLoader(catalystInstanceImplClass, ::getPayloadString)

        HookModules.forEach { it.onHook(packageParam) }
    }

}