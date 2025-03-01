package dev.wintry.xposed

import android.app.Activity
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.highcapable.yukihookapi.hook.type.android.ActivityClass
import com.highcapable.yukihookapi.hook.type.android.BundleClass
import com.highcapable.yukihookapi.hook.type.java.UnitType
import java.lang.ref.WeakReference

object RuntimeHelper {
    private var activityRef: WeakReference<Activity>? = WeakReference(null)
    private var onActivityCreateListener: MutableList<(Activity) -> Unit>? = mutableListOf()

    fun setupHook(packageParam: PackageParam): Unit = with (packageParam) {
        ActivityClass.method {
            name = "onCreate"
            param(BundleClass)
            returnType = UnitType
        }.hook {
            after {
                if (activityRef != null) return@after

                activityRef = WeakReference(instance<Activity>())

                if (onActivityCreateListener!!.isNotEmpty()) {
                    onActivityCreateListener!!.forEach { it(instance()) }
                    onActivityCreateListener!!.clear()
                    onActivityCreateListener = null
                }
            }
        }

    }

    fun getCurrentActivity(): Activity? = activityRef?.get()

    fun onActivityCreate(listener: (Activity) -> Unit) {
        when {
            getCurrentActivity() != null -> listener(getCurrentActivity()!!)
            else -> onActivityCreateListener?.add(listener)
        }
    }

    fun runOnUiThread(listener: Activity.() -> Unit) {
        onActivityCreate {
            it.runOnUiThread {
                listener(it)
            }
        }
    }
}