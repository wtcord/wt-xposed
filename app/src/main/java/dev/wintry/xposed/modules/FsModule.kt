package dev.wintry.xposed.modules

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.param.PackageParam
import dev.wintry.xposed.HookEntry.Companion.wintryDir
import dev.wintry.xposed.modules.base.HookModule

class FsModule: HookModule() {
    override fun onHook(param: PackageParam) = with(param) {
        val fileManagerModule = "com.discord.file_manager.FileModule".toClass().resolve()

        // Add 'wintry' dir to the constants
        fileManagerModule.firstConstructor().hook().after {
            @Suppress("UNCHECKED_CAST")
            val storageDirs =
                instanceClass!!.getDeclaredField("storageDirs").apply { isAccessible = true }
                    .get(instance) as HashMap<String, String>

            storageDirs["wintry"] = wintryDir.absolutePath
        }

        return@with
    }
}