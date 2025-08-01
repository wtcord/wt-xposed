package dev.wintry.xposed

import android.app.Activity
import android.os.Bundle
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.param.PackageParam
import java.lang.ref.WeakReference

object RuntimeHelper {
    private var activityRef: WeakReference<Activity>? = WeakReference(null)
    private var onActivityCreateListener: MutableList<(Activity) -> Unit>? = mutableListOf()

    fun setupHook(packageParam: PackageParam): Unit = with (packageParam) {
        Activity::class.resolve().firstMethod {
            name = "onCreate"
            parameters(Bundle::class)
        }.hook().after {
            if (activityRef != null) return@after

            activityRef = WeakReference(instance<Activity>())

            if (onActivityCreateListener!!.isNotEmpty()) {
                onActivityCreateListener!!.forEach { it(instance()) }
                onActivityCreateListener!!.clear()
                onActivityCreateListener = null
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