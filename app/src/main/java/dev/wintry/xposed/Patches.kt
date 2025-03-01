package dev.wintry.xposed

import android.widget.Toast
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.param.HookParam
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.highcapable.yukihookapi.hook.type.android.AssetManagerClass
import com.highcapable.yukihookapi.hook.type.java.BooleanType
import com.highcapable.yukihookapi.hook.type.java.MapClass
import com.highcapable.yukihookapi.hook.type.java.StringClass
import dev.wintry.xposed.HookEntry.Companion.HookModules
import dev.wintry.xposed.HookEntry.Companion.wintryDir
import dev.wintry.xposed.modules.UpdaterModule
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class CallInfo (
    @JsonNames("m") val module: String,
    @JsonNames("f") val function: String,
    @JsonNames("a") val args: ArrayList<JsonPrimitive>,
)

object Patches {
    fun PackageParam.hookScriptLoader(
        catalystInstanceImplClass: Class<*>,
        getPayloadString: () -> String,
    ) {
        val beforeHook: HookParam.() -> Unit = {
            val bundle = UpdaterModule.BUNDLE_FILE
            val fetchTask = UpdaterModule.fetchBundle()

            fetchTask.invokeOnCompletion { ex ->
                if (ex == null || ex is CancellationException) return@invokeOnCompletion

                val message = buildString {
                    append("Failed to fetch bundle, ")
                    if (bundle.exists()) append("using cached version")
                    else append("Wintry may not load")
                    append(": ${ex.message}")
                }

                RuntimeHelper.runOnUiThread {
                    //? Show alert dialog when cache is not available
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }

            if (!bundle.exists() || InitConfig.Current.forceUpdate) {
                runCatching { runBlocking { fetchTask.await() } }
            }

            if (bundle.exists()) {
                // Get some required functions
                val getOriginalFunc = { m: String ->
                    catalystInstanceImplClass.method { name = m }.get(instance).original()
                }

                val setGlobalVariable = getOriginalFunc("setGlobalVariable")
                val loadScriptFromFile = getOriginalFunc("loadScriptFromFile")

                for (script in File(wintryDir, "preload_scripts").walk()) {
                    if (script.isFile && script.extension == "js")
                        loadScriptFromFile.call(
                            script.absolutePath,
                            "preload:${script.name}",
                            args(2).boolean()
                        )
                }

                setGlobalVariable.call("__WINTRY_LOADER__", getPayloadString())
                loadScriptFromFile.call(bundle.absolutePath, "wintry", args(2).boolean())
            }
        }

        catalystInstanceImplClass.apply {
            method {
                name = "loadScriptFromAssets"
                param(AssetManagerClass, StringClass, BooleanType)
            }.hook().before(beforeHook)

            method {
                name = "loadScriptFromFile"
                param(StringClass, StringClass, BooleanType)
            }.hook().before(beforeHook)
        }
    }

    // This solution is pretty cursed. Registering our own native module would be more ideal, but:
    // 1. I'm not experienced enough in Android dev/modding to do something that complex
    // 2. Can we register our own native modules on iOS?
    //
    // This approach still can be optimized (a lot of parsing is done here), but not worth it for now since native functions are only called once in a while
    @OptIn(ExperimentalCoroutinesApi::class, ExperimentalSerializationApi::class,
        ExperimentalStdlibApi::class
    )
    fun PackageParam.hookForCallBridge() {
        val imageLoaderModuleClass = "com.facebook.react.modules.image.ImageLoaderModule".toClass()
        val toArrayList = "com.facebook.react.bridge.ReadableNativeArray".toClass().method {
            name = "toArrayList"
        }
        val makeNativeMap = "com.facebook.react.bridge.Arguments".toClass().method {
            name = "makeNativeMap"
            param(MapClass)
        }.get()

        imageLoaderModuleClass.method { name = "queryCache" }
            .hook()
            .before {
                @Suppress("UNCHECKED_CAST")
                val uris = toArrayList.get(args[0]).call() as? ArrayList<String> ?: return@before
                val result = runCatching {
                    val callInfo = Json.decodeFromString<CallInfo>(uris[1])

                    val acModule = HookModules.find { it.name == callInfo.module } ?: return@before
                    val acFunction = acModule::class.members.find { it.name == callInfo.function }
                        ?: return@before

                    acFunction.call(acModule, *callInfo.args.toTypedArray())
                }.getOrElse { return@before }

                val resolvePromise = args[1]!!.javaClass.method { name = "resolve" }.get(args[1])

                if (result is Deferred<*>) {
                    @Suppress("UNCHECKED_CAST")
                    val deferred = result as Deferred<JsonElement>
                    deferred.invokeOnCompletion { ex ->
                        val ret = when (ex) {
                            null -> {
                                val strRet = runCatching {
                                    Json.encodeToString(deferred.getCompleted())
                                }
                                if (strRet.isSuccess) {
                                    mapOf("ret" to strRet.getOrThrow())
                                } else {
                                    mapOf("err" to strRet.exceptionOrNull()?.stackTraceToString())
                                }
                            }
                            is CancellationException -> {
                                mapOf(
                                    "cancelled" to true,
                                    "reason" to ex.message
                                )
                            }
                            else -> {
                                mapOf("err" to ex.stackTraceToString())
                            }
                        }

                        val nativeMap = makeNativeMap.call(ret)
                        resolvePromise.call(nativeMap)
                    }
                } else {
                    val nativeMap = makeNativeMap.call(
                        runCatching {
                            Json.encodeToString(result as? JsonElement)
                        }.fold(
                            { value -> mapOf("ret" to value) },
                            { error -> mapOf("err" to error.stackTraceToString()) }
                        )
                    )

                    resolvePromise.call(nativeMap)
                }

                this.result = null
            }
    }
}
