package dev.wintry.xposed.modules

import com.highcapable.yukihookapi.hook.log.YLog
import dev.rushii.libunbound.LibUnbound
import dev.wintry.xposed.HookEntry.Companion.wintryDir
import dev.wintry.xposed.InitConfig
import dev.wintry.xposed.modules.annotations.RegisterMethod
import dev.wintry.xposed.modules.annotations.WintryModule
import dev.wintry.xposed.modules.base.HookModule
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import java.io.File

@Serializable
data class EndpointInfo(
    val paths: ArrayList<String>,
    val hash: String? = null
)

@Serializable
data class UpdateInfo(
    val url: String,
    val hash: String?
)

@WintryModule
class UpdaterModule: HookModule() {
    @RegisterMethod
    fun checkForUpdates(): Deferred<JsonElement> {
        return CoroutineScope(Dispatchers.IO).async {
            val updateInfo = UpdaterModule.checkForUpdates().await()
            return@async Json.encodeToJsonElement<UpdateInfo?>(updateInfo)
        }
    }

    @RegisterMethod
    fun updateBundle(): Deferred<JsonElement> {
        return CoroutineScope(Dispatchers.IO).async {
            val fetched = fetchBundle().await()
            return@async JsonPrimitive(fetched)
        }
    }

    companion object {
        val BUNDLE_FILE by lazy { File(wintryDir.absolutePath, "caches/bundle") }

        fun createHttpClient(): HttpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 30000
            }
            install(UserAgent) {
                agent = "WintryXposed"
            }
        }

        fun constructUrl(paths: Collection<String>): String {
            val hermesVersion = LibUnbound.getHermesRuntimeBytecodeVersion()
            return InitConfig.Current.baseUrl + (InitConfig.Current.bundlePath ?: when {
                paths.contains("/bundle.$hermesVersion.hbc") -> "bundle.$hermesVersion.hbc"
                paths.contains("/bundle.min.js") -> "bundle.min.js"
                else -> "bundle.js"
            })
        }

        fun checkForUpdates(passedClient: HttpClient? = null): Deferred<UpdateInfo?> = CoroutineScope(Dispatchers.IO).async {
            val targetFile = BUNDLE_FILE
            val client = passedClient ?: createHttpClient()

            try {
                val revision = File(targetFile.parentFile, "${targetFile.name}.revision")
                val infoRes = client.get(InitConfig.Current.baseUrl + "info.json")

                if (!infoRes.status.isSuccess()) {
                    val msg = "Failed to fetch endpoint info from ${infoRes.request.url}! " +
                              "Status: ${infoRes.status.value} (${infoRes.status.description})"
                    throw Exception(msg)
                }

                val info = Json.decodeFromString<EndpointInfo>(infoRes.bodyAsText())

                if (info.hash == null || !revision.exists() || info.hash != revision.readText()) {
                    return@async UpdateInfo(constructUrl(info.paths), info.hash)
                }

                return@async null
            } finally {
                // Close if the client is our own
                if (passedClient == null) client.close()
            }
        }

        fun fetchBundle(): Deferred<Boolean?> = CoroutineScope(Dispatchers.IO).async {
            val targetFile = BUNDLE_FILE
            val client = createHttpClient()

            try {
                val revision = File(targetFile.parentFile, "${targetFile.name}.revision")
                val info = checkForUpdates(client).await()

                if (info == null) {
                    YLog.info("No update available.")
                    return@async false
                }

                YLog.debug("Fetching JS bundle from ${info.url}")

                val response: HttpResponse = client.get(info.url)

                if (!response.status.isSuccess()) {
                    val msg = "Failed to fetch bundle from ${response.request.url}! " +
                              "Status: ${response.status.value} (${response.status.description})"

                    YLog.error(msg)
                    throw Exception(msg)
                }

                targetFile.writeBytes(response.body())

                // Write the rev or delete if hash is non-existent
                info.hash?.let { revision.writeText(it) } ?: revision.delete()

                YLog.info("Fetched JS bundle successfully!")
                return@async true
            } catch (e: Throwable) {
                throw e
            } finally {
                client.close()
            }
        }
    }
}