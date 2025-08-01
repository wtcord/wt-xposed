package dev.wintry.xposed.modules.base

import com.highcapable.yukihookapi.hook.param.PackageParam
import dev.wintry.xposed.modules.annotations.RegisterMethod
import kotlinx.serialization.json.JsonElement

abstract class HookModule() {
    val name: String
        get() = this::class.java.simpleName

    internal lateinit var packageParam: PackageParam

    val registeredFunctions = this::class
        .members
        .filter { it.annotations.any { annotation -> annotation is RegisterMethod } }
        .map { member ->
            val annotation = member.annotations.find { it is RegisterMethod } as RegisterMethod
            member.name to annotation.version
        }

    open fun getConstants(): Map<String, JsonElement> {
        return mapOf()
    }

    open fun onHook(param: PackageParam) = with(param) {
        packageParam = this
    }
}
