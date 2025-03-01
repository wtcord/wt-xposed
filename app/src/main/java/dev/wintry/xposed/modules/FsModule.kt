package dev.wintry.xposed.modules

import com.highcapable.yukihookapi.hook.param.PackageParam
import dev.wintry.xposed.HookEntry.Companion.wintryDir
import dev.wintry.xposed.modules.base.HookModule

class FsModule: HookModule() {
    override fun onHook(packageParam: PackageParam) = with(packageParam) {
        val fileManagerModule = "com.discord.file_manager.FileModule".toClass()

        // Add 'wintry' dir to the constants
        fileManagerModule.constructors.first().hook().after {
            val storageDirs =
                instanceClass!!.getDeclaredField("storageDirs").apply { isAccessible = true }
                    .get(instance) as HashMap<String, String>

            storageDirs["wintry"] = wintryDir.absolutePath
        }

        return@with
    }
}