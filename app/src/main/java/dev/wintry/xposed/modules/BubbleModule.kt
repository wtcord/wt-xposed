package dev.wintry.xposed.modules

import android.content.res.Resources
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
import com.highcapable.yukihookapi.hook.core.YukiMemberHookCreator
import com.highcapable.yukihookapi.hook.factory.method
import dev.wintry.xposed.modules.annotations.RegisterMethod
import dev.wintry.xposed.modules.base.HookModule
import dev.wintry.xposed.px
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull

class BubbleModule : HookModule() {
    private var configureAccessoriesMarginHook: YukiMemberHookCreator.MemberHookCreator.Result? = null
    private var configureAuthorHook: YukiMemberHookCreator.MemberHookCreator.Result? = null

    private var avatarCurveRadius = 12.px.toFloat()
    private var bubbleCurveRadius = 12.px.toFloat()
    private var chatBubbleColor = 0x66000000

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
        avatar: JsonPrimitive,
        bubble: JsonPrimitive,
        bubbleColor: JsonPrimitive
    ) {
        avatarCurveRadius = avatar.floatOrNull ?: avatarCurveRadius
        bubbleCurveRadius = bubble.floatOrNull ?: bubbleCurveRadius
        chatBubbleColor = bubbleColor.intOrNull ?: chatBubbleColor
    }

    override fun getConstants(): Map<String, JsonElement> {
        return mapOf(
            "available" to JsonPrimitive(true)
        )
    }

    private fun hookMessageView() = with(this.packageParam) {
        val MessageView = "com.discord.chat.presentation.message.MessageView".toClass()

        MessageView.apply {
            configureAccessoriesMarginHook = hookConfigureAccessoriesMargin()
            configureAuthorHook = hookConfigureAuthor()
        }
    }

    private fun Class<*>.hookConfigureAccessoriesMargin() = with(this@BubbleModule.packageParam) {
        method { name = "configureAccessoriesMargin" }.hook {
            after {
                val binding =
                    instanceClass!!.getDeclaredField("binding").apply { isAccessible = true }
                        .get(instance)
                val accessoriesView =
                    binding.javaClass.getField("accessoriesView").get(binding) as ViewGroup

                val marginLayoutParams = accessoriesView.layoutParams as MarginLayoutParams
                val topMargin = marginLayoutParams.topMargin

                marginLayoutParams.setMargins(
                    marginLayoutParams.leftMargin,
                    0,
                    marginLayoutParams.rightMargin,
                    marginLayoutParams.bottomMargin
                )

                accessoriesView.layoutParams = marginLayoutParams

                // "Move" the top margin set by the original function as padding.
                // This is made so that the padding also applies to the bubble
                accessoriesView.setPadding(
                    accessoriesView.paddingLeft,
                    topMargin + accessoriesView.paddingTop,
                    accessoriesView.paddingRight,
                    accessoriesView.paddingBottom
                )
            }
        }
    }

    private fun Class<*>.hookConfigureAuthor() = with(this@BubbleModule.packageParam) {
        method { name = "configureAuthor" }.hook {
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
        val linearLayout = viewGroup.children
            .filterIsInstance<LinearLayout>()
            .first { v -> v.children.any { c -> c.javaClass.simpleName == "ConstraintLayout" } }
                as ViewGroup

        applyBubbleBackground(viewGroup, linearLayout)
    }

    private fun setBubbleBackground(
        viewGroup: ViewGroup,
        leftMargin: Int,
        start: Boolean,
        end: Boolean,
    ) {
        val bubble = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(chatBubbleColor)
            if (start && end) {
                cornerRadius = bubbleCurveRadius
            } else {
                val radii = floatArrayOf(
                    0f,
                    0f,
                    0f,
                    0f,
                    bubbleCurveRadius,
                    bubbleCurveRadius,
                    bubbleCurveRadius,
                    bubbleCurveRadius
                ) // Curve on bottom
                if (start) radii.reverse() // Curve on top
                cornerRadii = radii
            }
        }

        val wrapper = InsetDrawable(bubble, leftMargin, 0, 6.px, 0)
        viewGroup.background = wrapper
    }

    private fun applyBubbleBackground(viewGroup: ViewGroup, linearLayout: ViewGroup) {
        // Check if the header is visible
        val messageHeader =
            linearLayout.children.first { c -> c.javaClass.simpleName == "ConstraintLayout" } as ViewGroup
        val headerVisible = messageHeader.children.first().visibility != View.GONE

        if (headerVisible) {
            linearLayout.apply {
                setBubbleBackground(this, 0, start = true, end = false)
                setPadding(12.px, 8.px, 0, 0)
                translationX = -6.px.toFloat()
            }
        } else {
            linearLayout.setPadding(0, 0, 0, 0)
        }

        viewGroup.children
            .filter { i -> i.javaClass.simpleName == "MessageAccessoriesView" }
            .firstOrNull()?.let { accessoriesView ->
                val messageAccessoriesDecoration =
                    accessoriesView.javaClass.getDeclaredField("messageAccessoriesDecoration")
                        .apply { isAccessible = true }.get(accessoriesView)
                val leftMarginPx =
                    messageAccessoriesDecoration.javaClass.getDeclaredField("leftMarginPx")
                        .apply { isAccessible = true }.get(messageAccessoriesDecoration) as Int

                setBubbleBackground(
                    accessoriesView as ViewGroup,
                    leftMarginPx,
                    start = !headerVisible,
                    true
                )

                accessoriesView.apply {
                    setPadding(12.px, if (headerVisible) 0 else 8.px, 6.px, 8.px)
                    translationX = -6.px.toFloat()
                }
            }
    }
}
