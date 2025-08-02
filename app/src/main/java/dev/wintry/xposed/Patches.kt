package dev.wintry.xposed

import android.content.res.AssetManager
import android.content.res.Resources
import android.widget.Toast
import com.highcapable.kavaref.KavaRef
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.core.api.reflect.invokeOriginal
import com.highcapable.yukihookapi.hook.param.HookParam
import com.highcapable.yukihookapi.hook.param.PackageParam
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
            Resources::class.resolve()
                .firstMethod {
                    name = "getIdentifier"
                    parameters(String::class, String::class, String::class)
                }.hook().before {
                    if (args[2] == packageName) args[2] = "com.discord"
                }
        }
    }

    fun PackageParam.hookScriptLoader(
        jsBundleLoaderDelegateClasses: Array<KavaRef.MemberScope<Any>>,
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
                val clazz = instance.javaClass.resolve()

                val loadScriptFromFile = clazz
                    .firstMethod { name = "loadScriptFromFile" }
                    .of(instance)

                // TODO: Only in CatalystInstanceImpl, remove once CatalystInstanceImpl is totally obsolete
                val setGlobalVariableFunc = clazz
                    .optional(silent = true)
                    .firstMethodOrNull { name = "setGlobalVariable" }
                    ?.of(instance)

                val async = args(2).boolean()

                val setGlobalVariable =
                    if (setGlobalVariableFunc != null) { key: String, value: String ->
                        setGlobalVariableFunc.invokeOriginal(key, value)
                    } else { key: String, value: String ->
                        // Bridgeless mode lacks a direct `setGlobalVariable` method,
                        // so we fall back to injecting a script file that sets the variable manually.
                        // This workaround is slower and a bit hacky, but works for now
                        val tmpScript =
                            File(appContext!!.cacheDir, "wt_setGlobalVariableTemp/$key.js")
                        tmpScript.parentFile?.mkdirs()

                        tmpScript.bufferedWriter().use { writer ->
                            writer.write("this[")
                            writer.write(Json.encodeToString(key))
                            writer.write("]=")
                            writer.write(value)
                        }

                        loadScriptFromFile.invokeOriginal(
                            tmpScript.absolutePath,
                            tmpScript.name,
                            async
                        )
                    }

                val preloadDir = File(wintryDir, "preload_scripts")
                if (preloadDir.exists()) {
                    for (script in preloadDir.walk()) {
                        if (script.isFile && script.extension == "js") {
                            loadScriptFromFile.invokeOriginal(
                                script.absolutePath,
                                "preload:${script.name}",
                                async
                            )
                        }
                    }
                }

                val kvDir = File(wintryDir, "kv")
                if (kvDir.exists()) {
                    for (file in kvDir.walk()) {
                        if (file.isFile) {
                            setGlobalVariable(
                                "__wt_kv/${file.name}",
                                Json.encodeToString(file.readText())
                            )
                        }
                    }
                }

                val tmpFile = File(bundle.parentFile, "${bundle.name}.tmp")

                // Create a copy of the bundle to avoid overwriting the original
                bundle.copyTo(tmpFile, overwrite = true)

                setGlobalVariable("__WINTRY_LOADER__", getPayloadString())
                loadScriptFromFile.invokeOriginal(tmpFile.absolutePath, "wintry", async)
            }
        }

        jsBundleLoaderDelegateClasses.forEach {
            it.apply {
            firstMethod {
                name = "loadScriptFromAssets"
                parameters(AssetManager::class, String::class, Boolean::class)
            }.hook().before(beforeHook)

            firstMethod {
                name = "loadScriptFromFile"
                parameters(String::class, String::class, Boolean::class)
            }.hook().before(beforeHook)
            }
        }
    }

    // This solution is pretty cursed. Registering our own native module would be more ideal, but:
    // 1. I'm not experienced enough in Android dev/modding to do something that complex
    // 2. Can we register our own native modules on iOS?
    //
    // This approach still can be optimized (a lot of parsing is done here), but not worth it for now since native functions are only called once in a while
    fun PackageParam.hookImageQueryCache() {
        val imageLoaderModuleClass =
            "com.facebook.react.modules.image.ImageLoaderModule".toClass().resolve()
        val readableArrayClass = "com.facebook.react.bridge.ReadableNativeArray".toClass().resolve()
        val toArrayListMethod = readableArrayClass.firstMethod { name = "toArrayList" }

        val makeNativeMapMethod = "com.facebook.react.bridge.Arguments"
            .toClass()
            .resolve()
            .firstMethod {
                name = "makeNativeMap"
                parameters(Map::class)
            }

        val queryCacheMethod = imageLoaderModuleClass.firstMethod { name = "queryCache" }

        queryCacheMethod.hook().before {
            val inputArray = args[0]
            val promiseArg = args[1]

            // Sanity check early
            val uris =
                toArrayListMethod.of(inputArray).invokeOriginal() as? ArrayList<*> ?: return@before
            if (uris.size <= 1 || uris[0] != "__wintry_bridge") return@before

            // Cast items after we know it's valid
            @Suppress("UNCHECKED_CAST")
            val uriList = uris as ArrayList<String>

            val resolvePromiseMethod = promiseArg
                ?.javaClass?.resolve()
                ?.firstMethod { name = "resolve" }
                ?.of(promiseArg)
                ?: return@before

            val result = runCatching { processCall(uriList[1]) }
            handleResult(result) { processedResult ->
                val nativeMap = makeNativeMapMethod.invokeOriginal(processedResult)
                resolvePromiseMethod.invokeOriginal(nativeMap)
            }

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

    private fun handleResult(result: Result<Any?>, resolvePromise: (Map<*, *>) -> Unit) {
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
