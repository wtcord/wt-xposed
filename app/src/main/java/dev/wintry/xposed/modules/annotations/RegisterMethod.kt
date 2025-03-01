package dev.wintry.xposed.modules.annotations

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RegisterMethod(val version: Int = 0)
