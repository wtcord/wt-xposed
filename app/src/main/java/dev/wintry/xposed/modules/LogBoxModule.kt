package dev.wintry.xposed.modules

import android.app.AndroidAppHelper
import android.content.Intent
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.param.PackageParam
import dev.wintry.xposed.modules.base.HookModule
import kotlin.system.exitProcess

class LogBoxModule: HookModule() {
    override fun onHook(param: PackageParam) {
        super.onHook(param)
        hookLogBox()
    }

    private fun hookLogBox() = with(this.packageParam) {
        "com.discord.bridge.DCDReactNativeHost".toClass().resolve()
            .firstMethod { name = "getUseDeveloperSupport" }.hook().replaceToTrue()

        "com.facebook.react.devsupport.BridgeDevSupportManager".toClass().resolve()
            .firstMethod { name = "handleReloadJS" }.hook().before {
            val application = AndroidAppHelper.currentApplication()
            val intent = application.packageManager.getLaunchIntentForPackage(application.packageName)

            application.startActivity(Intent.makeRestartActivityTask(intent!!.component))
            exitProcess(0)
        }
    }
}