package dev.wintry.xposed

import android.content.Context
import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.hook.log.YLog.Configs.tag
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

class XposedEntry: IXposedHookLoadPackage {
    private val hookEntryInstance = HookEntry()

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam?) {
        val contextWrapperClass = lpparam!!.classLoader.loadClass("android.content.ContextWrapper")
        val attachBaseContextMethod = contextWrapperClass.getDeclaredMethod("attachBaseContext", Context::class.java)

        XposedBridge.hookMethod(attachBaseContextMethod, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val context = param.args[0] as Context

                YukiHookAPI.configs {
                    tag = "WintryXposed"
                }

                YukiHookAPI.encase(context) {
                    with (hookEntryInstance) {
                        this@encase.loadWintry()
                    }
                }
            }
        })
    }
}