package io.github.nitsuya.aa.display.xposed.util

import android.annotation.SuppressLint
import android.content.res.Resources
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import de.robv.android.xposed.callbacks.XCallback
import de.robv.android.xposed.IXposedHookZygoteInit
import java.lang.reflect.Constructor
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Minimal local stand-in for the EzXHelper APIs this module actually used.
 * Keeps hook/reflect call sites stable without a JitPack dependency.
 */
object ModuleInit {
    @Volatile
    var classLoader: ClassLoader = ClassLoader.getSystemClassLoader()
        private set

    @Volatile
    private var modulePath: String = ""

    fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
    }

    fun initHandleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        classLoader = lpparam.classLoader
    }

    @SuppressLint("DiscouragedPrivateApi")
    fun addModuleAssetPath(resources: Resources) {
        val path = modulePath
        if (path.isEmpty()) return
        try {
            val method = resources.assets.javaClass.getDeclaredMethod(
                "addAssetPath",
                String::class.java,
            )
            method.isAccessible = true
            method.invoke(resources.assets, path)
        } catch (_: Throwable) {
        }
    }
}

typealias Hooker = (XC_MethodHook.MethodHookParam) -> Unit
typealias MethodCondition = Method.() -> Boolean
typealias ConstructorCondition = Constructor<*>.() -> Boolean

@JvmInline
value class Args(val args: Array<out Any?>)

@JvmInline
value class ArgTypes(val argTypes: Array<out Class<*>>)

@Suppress("NOTHING_TO_INLINE")
inline fun args(vararg args: Any?) = Args(args)

@Suppress("NOTHING_TO_INLINE")
inline fun argTypes(vararg argTypes: Class<*>) = ArgTypes(argTypes)

inline fun <T> tryOrNull(block: () -> T?): T? = try {
    block()
} catch (_: Throwable) {
    null
}

fun <R> Result<R>.logexIfThrow(msg: String = "") {
    exceptionOrNull()?.let { log("AADisplay_Reflect", msg, it) }
}

val Member.isStatic: Boolean
    get() = Modifier.isStatic(modifiers)

fun loadClass(
    className: String,
    classLoader: ClassLoader = ModuleInit.classLoader,
): Class<*> = classLoader.loadClass(className)

fun findMethod(
    clz: Class<*>,
    findSuper: Boolean = false,
    condition: MethodCondition,
): Method = findMethodOrNull(clz, findSuper, condition) ?: throw NoSuchMethodException()

fun findMethodOrNull(
    clz: Class<*>,
    findSuper: Boolean = false,
    condition: MethodCondition,
): Method? {
    var c = clz
    c.declaredMethods.firstOrNull { it.condition() }?.let {
        it.isAccessible = true
        return it
    }
    if (findSuper) {
        while (c.superclass?.also { c = it } != null) {
            c.declaredMethods.firstOrNull { it.condition() }?.let {
                it.isAccessible = true
                return it
            }
        }
    }
    return null
}

fun findMethod(
    className: String,
    classLoader: ClassLoader = ModuleInit.classLoader,
    findSuper: Boolean = false,
    condition: MethodCondition,
): Method = findMethod(loadClass(className, classLoader), findSuper, condition)

fun findAllConstructors(
    className: String,
    classLoader: ClassLoader = ModuleInit.classLoader,
    condition: ConstructorCondition,
): List<Constructor<*>> = loadClass(className, classLoader).declaredConstructors.filter(condition)

fun Method.hookBefore(
    priority: Int = XCallback.PRIORITY_DEFAULT,
    hook: Hooker,
): XC_MethodHook.Unhook = XposedBridge.hookMethod(this, object : XC_MethodHook(priority) {
    override fun beforeHookedMethod(param: MethodHookParam) {
        try {
            hook(param)
        } catch (t: Throwable) {
            log("AADisplay_Reflect", "hookBefore failed: $name", t)
        }
    }
})

fun Method.hookAfter(
    priority: Int = XCallback.PRIORITY_DEFAULT,
    hook: Hooker,
): XC_MethodHook.Unhook = XposedBridge.hookMethod(this, object : XC_MethodHook(priority) {
    override fun afterHookedMethod(param: MethodHookParam) {
        try {
            hook(param)
        } catch (t: Throwable) {
            log("AADisplay_Reflect", "hookAfter failed: $name", t)
        }
    }
})

fun Constructor<*>.hookAfter(
    priority: Int = XCallback.PRIORITY_DEFAULT,
    hook: Hooker,
): XC_MethodHook.Unhook = XposedBridge.hookMethod(this, object : XC_MethodHook(priority) {
    override fun afterHookedMethod(param: MethodHookParam) {
        try {
            hook(param)
        } catch (t: Throwable) {
            log("AADisplay_Reflect", "ctor hookAfter failed: $name", t)
        }
    }
})

fun Iterable<Constructor<*>>.hookAfter(
    priority: Int = XCallback.PRIORITY_DEFAULT,
    hook: Hooker,
): List<XC_MethodHook.Unhook> = map { it.hookAfter(priority, hook) }

private fun Array<Class<*>>.sameAs(other: Array<out Class<*>>): Boolean {
    if (size != other.size) return false
    for (i in indices) {
        if (this[i] != other[i]) return false
    }
    return true
}

private fun Any.resolveMethod(
    methodName: String,
    returnType: Class<*>?,
    isStatic: Boolean,
    argTypes: ArgTypes,
): Method {
    if (methodName.isBlank()) throw IllegalArgumentException("Method name must not be empty!")
    var c: Class<*> = if (this is Class<*>) this else javaClass
    do {
        c.declaredMethods
            .asSequence()
            .filter { it.name == methodName }
            .filter { it.parameterTypes.size == argTypes.argTypes.size }
            .filter { returnType == null || it.returnType == returnType }
            .filter { it.parameterTypes.sameAs(argTypes.argTypes) }
            .filter { it.isStatic == isStatic }
            .firstOrNull()
            ?.let {
                it.isAccessible = true
                return it
            }
    } while (c.superclass?.also { c = it } != null)
    throw NoSuchMethodException(
        "Name:$methodName, Static: $isStatic, ArgTypes:${argTypes.argTypes.joinToString(",")}"
    )
}

fun Any.invokeMethod(
    methodName: String,
    args: Args = args(),
    argTypes: ArgTypes = argTypes(),
    returnType: Class<*>? = null,
): Any? {
    if (args.args.size != argTypes.argTypes.size) {
        throw IllegalArgumentException("Method args size must equals argTypes size!")
    }
    val method = try {
        resolveMethod(methodName, returnType, isStatic = false, argTypes = argTypes)
    } catch (_: NoSuchMethodException) {
        return null
    }
    return if (args.args.isEmpty()) method.invoke(this) else method.invoke(this, *args.args)
}

fun Class<*>.newInstance(
    args: Args = args(),
    argTypes: ArgTypes = argTypes(),
): Any? {
    if (args.args.size != argTypes.argTypes.size) {
        throw IllegalArgumentException("Method args size must equals argTypes size!")
    }
    return tryOrNull {
        val constructor = if (argTypes.argTypes.isNotEmpty()) {
            getDeclaredConstructor(*argTypes.argTypes)
        } else {
            getDeclaredConstructor()
        }
        constructor.isAccessible = true
        if (args.args.isEmpty()) constructor.newInstance() else constructor.newInstance(*args.args)
    }
}

private fun Any.field(fieldName: String, fieldType: Class<*>? = null): java.lang.reflect.Field {
    if (fieldName.isBlank()) throw IllegalArgumentException("Field name must not be empty!")
    var c: Class<*> = if (this is Class<*>) this else javaClass
    do {
        c.declaredFields
            .firstOrNull { f ->
                !f.isStatic &&
                    (fieldType == null || f.type == fieldType) &&
                    f.name == fieldName
            }
            ?.let {
                it.isAccessible = true
                return it
            }
    } while (c.superclass?.also { c = it } != null)
    throw NoSuchFieldException(
        "Name: $fieldName, Type: ${fieldType?.name ?: "ignore"}"
    )
}

fun Any.getObject(objName: String, type: Class<*>? = null): Any =
    field(objName, type).get(this)!!

@Suppress("UNCHECKED_CAST")
fun <T> Any.getObjectAs(objName: String, type: Class<*>? = null): T =
    getObject(objName, type) as T
