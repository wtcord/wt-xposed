package dev.wintry.xposed

import android.widget.Toast
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.param.HookParam
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.highcapable.yukihookapi.hook.type.android.AssetManagerClass
import com.highcapable.yukihookapi.hook.type.android.ResourcesClass
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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.jvm.javaType

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class CallInfo (
    @JsonNames("m") val module: String,
    @JsonNames("f") val function: String,
    @JsonNames("a") val args: ArrayList<JsonPrimitive>,
)

fun decodeJsonPrimitive(element: JsonPrimitive, clazz: Class<*>, nullable: Boolean): Any? {
    if (element.contentOrNull == null) {
        if (!nullable) throw IllegalArgumentException("Element is null")
        return null
    }

    // This may hurt your soul, I'm sorry
    return when (clazz) {
        Int::class.java, Int::class.javaObjectType -> element.int
        Double::class.java, Double::class.javaObjectType -> element.double
        Float::class.java, Float::class.javaObjectType -> element.float
        Long::class.java, Long::class.javaObjectType -> element.long
        Boolean::class.java, Boolean::class.javaObjectType -> element.boolean
        String::class.java, String::class.javaObjectType -> element.content
        else -> throw Exception("Unknown type of class ${clazz.simpleName} used for module parameter") // Unsupported type
    }
}

object Patches {
    // Temp fix: Fighting the side effects of changing the package name in the manifest
    fun PackageParam.hookPackageResourcesIdentifier() {
        if (packageName != "com.discord") {
            ResourcesClass.method {
                name = "getIdentifier"
                param(String::class.java, String::class.java, String::class.java)
            }.hook {
                before {
                    if (args[2] == packageName) args[2] = "com.discord"
                }
            }
        }
    }

    fun PackageParam.hookScriptLoader(
        catalystInstanceImplClass: Class<*>,
        getPayloadString: () -> String,
    ) {
        val beforeHook: HookParam.() -> Unit = {
            val bundle = UpdaterModule.BUNDLE_FILE

            if (!InitConfig.Current.skipUpdate || !bundle.exists()) {
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
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                }

                if (!bundle.exists() || InitConfig.Current.forceUpdate) {
                    runCatching { runBlocking { fetchTask.await() } }
                }
            }

            if (bundle.exists()) {
                val getOriginalFunc = { m: String ->
                    catalystInstanceImplClass.method { name = m }.get(instance).original()
                }

                val setGlobalVariable = getOriginalFunc("setGlobalVariable")
                val loadScriptFromFile = getOriginalFunc("loadScriptFromFile")

                val preloadDir = File(wintryDir, "preload_scripts")
                if (preloadDir.exists()) {
                    for (script in preloadDir.walk()) {
                        if (script.isFile && script.extension == "js") {
                            loadScriptFromFile.call(
                                script.absolutePath,
                                "preload:${script.name}",
                                args(2).boolean()
                            )
                        }
                    }
                }

                val kvDir = File(wintryDir, "kv")
                if (kvDir.exists()) {
                    for (file in kvDir.walk()) {
                        if (file.isFile) {
                            setGlobalVariable.call(
                                "__wt_kv/${file.name}",
                                Json.encodeToString(file.readText())
                            )
                        }
                    }
                }

                val tmpFile = File(bundle.parentFile, "${bundle.name}.tmp")

                // Create a copy of the bundle to avoid overwriting the original
                bundle.copyTo(tmpFile, overwrite = true)

                setGlobalVariable.call("__WINTRY_LOADER__", getPayloadString())
                loadScriptFromFile.call(tmpFile.absolutePath, "wintry", args(2).boolean())
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
    fun PackageParam.hookImageQueryCache() {
        val imageLoaderModuleClass = "com.facebook.react.modules.image.ImageLoaderModule".toClass()
        val toArrayList = "com.facebook.react.bridge.ReadableNativeArray".toClass().method {
            name = "toArrayList"
        }

        imageLoaderModuleClass.method { name = "queryCache" }
            .hook()
            .before {
                @Suppress("UNCHECKED_CAST")
                val uris = toArrayList.get(args[0]).call() as? ArrayList<String> ?: return@before
                if (uris.firstOrNull() != "__wintry_bridge" || uris.size <= 1) return@before

                val resolvePromise = args[1]?.javaClass?.method { name = "resolve" }?.get(args[1])
                    ?: return@before

                val makeNativeMap = "com.facebook.react.bridge.Arguments".toClass().method {
                    name = "makeNativeMap"
                    param(MapClass)
                }.get()

                val result = runCatching { processCall(uris[1]) }
                handleResult(result) { resolvePromise.call(makeNativeMap.call(it)) }

                this.result = null
            }
    }

    private fun processCall(callInfoJson: String): Any? {
        val callInfo = Json.decodeFromString<CallInfo>(callInfoJson)
        val acModule = HookModules.find { it.name == callInfo.module } ?: return Unit
        val acFunction = acModule::class.members.find { it.name == callInfo.function } ?: return Unit

        require(acFunction.parameters.count() - 1 == callInfo.args.count()) { "Invalid number of arguments" }

        return acFunction.call(acModule, *callInfo.args.mapIndexed { i, a ->
            val type = acFunction.parameters[i + 1].type
            val clazz = type.javaType as? Class<*>
            clazz?.takeIf { JsonPrimitive::class.java.isAssignableFrom(it) }?.let { a }
                ?: decodeJsonPrimitive(a, clazz!!, type.isMarkedNullable)
        }.toTypedArray())
    }

    private fun PackageParam.handleResult(result: Result<Any?>, resolvePromise: (Map<*, *>) -> Unit) {
        if (result.isFailure) {
            resolvePromise(mapOf("err" to result.exceptionOrNull()?.stackTraceToString()))
            return
        }

        when (val value = result.getOrThrow()) {
            is Deferred<*> -> handleDeferred(value, resolvePromise)
            else -> resolvePromise(serializeResult(value))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun handleDeferred(deferred: Deferred<*>, resolvePromise: (Map<*, *>) -> Unit) {
        deferred.invokeOnCompletion { ex ->
            val response = when (ex) {
                null -> runCatching { Json.encodeToString(deferred.getCompleted() as JsonElement) }
                    .fold({ mapOf("ret" to it) }, { mapOf("err" to it.stackTraceToString()) })
                is CancellationException -> mapOf("cancelled" to true, "reason" to ex.message)
                else -> mapOf("err" to ex.stackTraceToString())
            }
            resolvePromise(response)
        }
    }

    private fun serializeResult(result: Any?): Map<String, String?> =
        runCatching { Json.encodeToString(result as? JsonElement) }
            .fold({ mapOf("ret" to it) }, { mapOf("err" to it.stackTraceToString()) })
}
