package dev.wintry.xposed.modules

import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.children
import com.highcapable.kavaref.KavaRef
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.core.YukiMemberHookCreator
import dev.wintry.xposed.modules.annotations.RegisterMethod
import dev.wintry.xposed.modules.base.HookModule
import dev.wintry.xposed.px

class BubbleModule : HookModule() {
    private var configureAccessoriesMarginHook: YukiMemberHookCreator.MemberHookCreator.Result? = null
    private var configureAuthorHook: YukiMemberHookCreator.MemberHookCreator.Result? = null

    private var avatarCurveRadius = DEFAULT_AVATAR_CURVE_RADIUS
    private var bubbleCurveRadius = DEFAULT_BUBBLE_CURVE_RADIUS
    private var chatBubbleColor = DEFAULT_BUBBLE_COLOR

    companion object {
        private val DEFAULT_AVATAR_CURVE_RADIUS = 12.px.toFloat()
        private val DEFAULT_BUBBLE_CURVE_RADIUS = 12.px.toFloat()
        private const val DEFAULT_BUBBLE_COLOR = 0x66000000
        private val PADDING_SMALL = 6.px
        private val PADDING_MEDIUM = 8.px
        private val PADDING_LARGE = 12.px
    }

    @RegisterMethod
    fun hookBubbles() {
        hookMessageView()
    }

    @RegisterMethod
    fun unhookBubbles() {
        configureAccessoriesMarginHook?.remove()
        configureAccessoriesMarginHook = null

        configureAuthorHook?.remove()
        configureAuthorHook = null
    }

    @RegisterMethod
    fun configure(
        avatar: Float?,
        bubble: Float?,
        bubbleColor: Int?
    ) {
        avatarCurveRadius = avatar?.px ?: avatarCurveRadius
        bubbleCurveRadius = bubble?.px ?: bubbleCurveRadius
        chatBubbleColor = bubbleColor ?: chatBubbleColor
    }

    private fun hookMessageView() = with(this.packageParam) {
        val MessageView = "com.discord.chat.presentation.message.MessageView".toClass().resolve()
        MessageView.apply {
            configureAccessoriesMarginHook = hookConfigureAccessoriesMargin()
            configureAuthorHook = hookConfigureAuthor()
        }
    }

    private fun KavaRef.MemberScope<*>.hookConfigureAccessoriesMargin() =
        with(this@BubbleModule.packageParam) {
            firstMethod { name = "configureAccessoriesMargin" }.hook {
            after {
                val binding = instanceClass!!.getDeclaredField("binding").apply { isAccessible = true }.get(instance)
                val accessoriesView = binding.javaClass.getField("accessoriesView").get(binding) as ViewGroup
                adjustMarginsForAccessories(accessoriesView)
            }
        }
    }

    private fun adjustMarginsForAccessories(view: ViewGroup) {
        val marginLayoutParams = view.layoutParams as MarginLayoutParams
        val topMargin = marginLayoutParams.topMargin

        marginLayoutParams.setMargins(marginLayoutParams.leftMargin, 0, marginLayoutParams.rightMargin, marginLayoutParams.bottomMargin)
        view.layoutParams = marginLayoutParams

        view.setPadding(view.paddingLeft, topMargin + view.paddingTop, view.paddingRight, view.paddingBottom)
    }

    private fun KavaRef.MemberScope<*>.hookConfigureAuthor() =
        with(this@BubbleModule.packageParam) {
            firstMethod { name = "configureAuthor" }.hook {
            after {
                val view = instance<ViewGroup>()
                applyRoundedSquareProfilePicture(view)
                applyBubbleChat(view)
            }
        }
    }

    private fun applyRoundedSquareProfilePicture(viewGroup: ViewGroup) {
        viewGroup.children.filterIsInstance<ImageView>().firstOrNull()?.apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View?, outline: Outline?) {
                    outline?.setRoundRect(0, 0, view!!.width, view.height, avatarCurveRadius)
                }
            }
        }
    }

    private fun applyBubbleChat(viewGroup: ViewGroup) {
        val linearLayout = viewGroup.children.filterIsInstance<LinearLayout>().firstOrNull { v -> v.children.any { c -> c.javaClass.simpleName == "ConstraintLayout" } } as? ViewGroup ?: return
        applyBubbleBackground(viewGroup, linearLayout)
    }

    private fun applyBubbleBackground(viewGroup: ViewGroup, linearLayout: ViewGroup) {
        val messageHeader = linearLayout.children.firstOrNull { c -> c.javaClass.simpleName == "ConstraintLayout" } as? ViewGroup ?: return
        val headerVisible = messageHeader.children.firstOrNull()?.visibility != View.GONE

        if (headerVisible) {
            linearLayout.setBubbleBackground(0, start = true, end = false)
            linearLayout.setPadding(PADDING_LARGE, PADDING_MEDIUM, 0, 0)
            linearLayout.translationX = -PADDING_SMALL.toFloat()
        } else {
            linearLayout.setPadding(0, 0, 0, 0)
        }

        viewGroup.children.firstOrNull { i -> i.javaClass.simpleName == "MessageAccessoriesView" }?.let { accessoriesView ->
            setAccessoryBubbleBackground(accessoriesView as ViewGroup, !headerVisible)
        }
    }

    private fun setAccessoryBubbleBackground(accessoriesView: ViewGroup, start: Boolean) {
        val messageAccessoriesDecoration = accessoriesView.javaClass.getDeclaredField("messageAccessoriesDecoration").apply { isAccessible = true }.get(accessoriesView)
        val leftMarginPx = messageAccessoriesDecoration.javaClass.getDeclaredField("leftMarginPx").apply { isAccessible = true }.get(messageAccessoriesDecoration) as Int

        accessoriesView.setBubbleBackground(leftMarginPx, start, true)
        accessoriesView.setPadding(PADDING_LARGE, if (start) PADDING_MEDIUM else 0, PADDING_SMALL, PADDING_MEDIUM)
        accessoriesView.translationX = -PADDING_SMALL.toFloat()
    }

    private fun ViewGroup.setBubbleBackground(leftMargin: Int, start: Boolean, end: Boolean) {
        val bubble = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(chatBubbleColor)
            cornerRadii = FloatArray(8) { i ->
                when {
                    start && end -> bubbleCurveRadius
                    start && i < 4 -> bubbleCurveRadius // Top corners
                    !start && i >= 4 -> bubbleCurveRadius // Bottom corners
                    else -> 0f
                }
            }
        }
        background = InsetDrawable(bubble, leftMargin, 0, PADDING_SMALL, 0)
    }
}
