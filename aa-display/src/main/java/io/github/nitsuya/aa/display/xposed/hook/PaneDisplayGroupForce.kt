package io.github.nitsuya.aa.display.xposed.hook

import android.content.Context
import android.os.Binder
import android.os.ServiceManager
import android.util.SparseArray
import android.view.Display
import io.github.nitsuya.aa.display.xposed.util.log
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Force AA pane LogicalDisplays out of default DisplayGroup 0.
 *
 * Lineage A16 (OnePlus8): panes keep OWN_DISPLAY_GROUP / ALWAYS_UNLOCKED on
 * DisplayInfo but mDisplayGroupId stays 0, so they share the phone PowerGroup and
 * keyguard / ColorFade syncs onto the car panes. ALWAYS_UNLOCKED is ignored in
 * the default group.
 *
 * Strategy: set LogicalDisplay displayGroupName, call assignDisplayGroupLocked;
 * if still group 0, manually splice into a named DisplayGroup.
 */
object PaneDisplayGroupForce {
    private const val TAG = "AAD_PaneDisplayGroup"
    private const val GROUP_NAME = "AADisplay"
    /** Display.DEFAULT_DISPLAY_GROUP — @hide, use literal. */
    private const val DEFAULT_GROUP = 0

    @Volatile private var resolved = false
    private var dmsInstance: Any? = null
    private var syncRoot: Any? = null
    private var mapper: Any? = null
    private var getDisplayLocked: Method? = null
    private var assignDisplayGroupLocked: Method? = null
    private var setDisplayGroupNameLocked: Method? = null
    private var getDisplayGroupIdLocked: Method? = null
    private var updateDisplayGroupIdLocked: Method? = null

    fun ensureResolved() {
        if (resolved) return
        resolved = true
        runCatching {
            dmsInstance = findDms() ?: return@runCatching
            val dms = dmsInstance!!
            syncRoot = findFieldValue(dms, "mSyncRoot") ?: findFieldValue(dms, "mLock")
            mapper = findFieldValue(dms, "mLogicalDisplayMapper") ?: return@runCatching
            val mapperObj = mapper!!

            getDisplayLocked = findMethod(mapperObj.javaClass, "getDisplayLocked") { m ->
                m.parameterTypes.isNotEmpty() &&
                    m.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            assignDisplayGroupLocked = findMethod(mapperObj.javaClass, "assignDisplayGroupLocked") { m ->
                m.parameterTypes.size == 1
            }

            log(
                TAG,
                "resolved mapper=${mapperObj.javaClass.simpleName} " +
                    "getDisplay=${getDisplayLocked != null} assign=${assignDisplayGroupLocked != null} " +
                    "syncRoot=${syncRoot != null}",
            )
        }.onFailure { log(TAG, "resolve failed", it) }
    }

    fun forceOwnGroup(displayId: Int, reason: String) {
        if (displayId == Display.INVALID_DISPLAY || displayId == Display.DEFAULT_DISPLAY) return
        ensureResolved()
        val mapperObj = mapper ?: return
        val getDisplay = getDisplayLocked ?: return
        val root = syncRoot

        val identity = Binder.clearCallingIdentity()
        try {
            fun doForce() {
                val display = invokeGetDisplay(mapperObj, getDisplay, displayId) ?: return
                resolveLogicalDisplayMethods(display)
                setDisplayGroupNameLocked?.invoke(display, GROUP_NAME)
                assignDisplayGroupLocked?.invoke(mapperObj, display)
                var groupId = readGroupId(display)
                if (groupId == DEFAULT_GROUP) {
                    manualMoveToOwnGroup(mapperObj, display)
                    groupId = readGroupId(display)
                }
                // Lineage A16: mBaseDisplayInfo.displayGroupId updates, but
                // mOverrideDisplayInfo often stays on group 0 — Power/userActivity
                // then still routes HU touch to the phone PowerGroup.
                if (groupId != DEFAULT_GROUP && groupId >= 0) {
                    syncOverrideDisplayGroupId(display, groupId)
                }
                log(TAG, "forceOwnGroup id=$displayId → group=$groupId ($reason)")
            }
            if (root != null) {
                synchronized(root) { doForce() }
            } else {
                doForce()
            }
        } catch (e: Throwable) {
            log(TAG, "forceOwnGroup($displayId) failed", e)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    /**
     * AaDisplayActivity presentation (Gearhead PRIVATE VD): same Lineage A16 trap as
     * panes — stays in DisplayGroup 0 without ALWAYS_UNLOCKED, so phone keyguard drops
     * [IInputManager.inject] (FacetBar → touchAaDisplay dead under lock; peel used
     * SplitLockedPeelController as a bypass). Call when Recents / picker opens.
     */
    fun forceInteractiveUnderKeyguard(displayId: Int, reason: String) {
        if (displayId == Display.INVALID_DISPLAY || displayId == Display.DEFAULT_DISPLAY) return
        forceOwnGroup(displayId, reason)
        ensureResolved()
        val mapperObj = mapper ?: return
        val getDisplay = getDisplayLocked ?: return
        val root = syncRoot
        val flag = alwaysUnlockedDisplayFlag() ?: return
        val identity = Binder.clearCallingIdentity()
        try {
            fun doPatch() {
                val display = invokeGetDisplay(mapperObj, getDisplay, displayId) ?: return
                var changed = false
                changed = orDisplayFlags(findFieldValue(display, "mBaseDisplayInfo"), flag) || changed
                changed = orDisplayFlags(findFieldValue(display, "mOverrideDisplayInfo"), flag) || changed
                val infoObj = findFieldValue(display, "mInfo")
                if (infoObj != null) {
                    val cached = findMethod(infoObj.javaClass, "get") { m ->
                        m.parameterTypes.isEmpty()
                    }?.invoke(infoObj)
                    if (cached != null) {
                        changed = orDisplayFlags(cached, flag) || changed
                    } else if (infoObj.javaClass.name.endsWith("DisplayInfo")) {
                        changed = orDisplayFlags(infoObj, flag) || changed
                    }
                }
                if (changed) {
                    log(TAG, "ALWAYS_UNLOCKED id=$displayId ($reason)")
                }
            }
            if (root != null) {
                synchronized(root) { doPatch() }
            } else {
                doPatch()
            }
        } catch (e: Throwable) {
            log(TAG, "forceInteractiveUnderKeyguard($displayId) failed", e)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun orDisplayFlags(info: Any?, flag: Int): Boolean {
        if (info == null) return false
        val field = findField(info.javaClass, "flags") ?: return false
        field.isAccessible = true
        val cur = field.getInt(info)
        if (cur and flag != 0) return false
        field.setInt(info, cur or flag)
        return true
    }

    private fun alwaysUnlockedDisplayFlag(): Int? {
        return try {
            Class.forName("android.view.Display")
                .getField("FLAG_ALWAYS_UNLOCKED")
                .getInt(null)
        } catch (_: Throwable) {
            // AOSP Display.FLAG_ALWAYS_UNLOCKED — not VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED.
            1 shl 9
        }
    }

    private fun manualMoveToOwnGroup(mapperObj: Any, display: Any) {
        val groupsObj = findFieldValue(mapperObj, "mDisplayGroups") ?: return
        @Suppress("UNCHECKED_CAST")
        val groups = groupsObj as SparseArray<Any>
        val defaultGroup = groups.get(DEFAULT_GROUP)

        var newGroupId = 0
        val byName = findFieldValue(mapperObj, "mDisplayGroupIdsByName")
        if (byName is MutableMap<*, *>) {
            @Suppress("UNCHECKED_CAST")
            val map = byName as MutableMap<Any?, Any?>
            val existing = map[GROUP_NAME]
            if (existing is Number && existing.toInt() != 0) {
                newGroupId = existing.toInt()
            } else {
                val nextField = findField(mapperObj.javaClass, "mNextNonDefaultGroupId")
                nextField?.isAccessible = true
                newGroupId = nextField?.getInt(mapperObj) ?: (groups.size() + 1).coerceAtLeast(2)
                nextField?.setInt(mapperObj, newGroupId + 1)
                map[GROUP_NAME] = newGroupId
            }
        } else {
            val nextField = findField(mapperObj.javaClass, "mNextNonDefaultGroupId")
            nextField?.isAccessible = true
            newGroupId = nextField?.getInt(mapperObj) ?: 2
            nextField?.setInt(mapperObj, newGroupId + 1)
        }
        if (newGroupId == 0) newGroupId = 2

        var newGroup = groups.get(newGroupId)
        if (newGroup == null) {
            val dgClass = Class.forName("com.android.server.display.DisplayGroup")
            newGroup = dgClass.getConstructor(Int::class.javaPrimitiveType).newInstance(newGroupId)
            groups.put(newGroupId, newGroup)
        }

        if (defaultGroup != null) {
            findMethod(defaultGroup.javaClass, "removeDisplayLocked") { m ->
                m.parameterTypes.size == 1
            }?.invoke(defaultGroup, display)
        }
        findMethod(newGroup!!.javaClass, "addDisplayLocked") { m ->
            m.parameterTypes.size == 1
        }?.invoke(newGroup, display)

        if (updateDisplayGroupIdLocked != null) {
            updateDisplayGroupIdLocked!!.invoke(display, newGroupId)
        } else {
            findField(display.javaClass, "mDisplayGroupId")?.apply { isAccessible = true }
                ?.setInt(display, newGroupId)
            runCatching {
                val base = findFieldValue(display, "mBaseDisplayInfo")
                if (base != null) {
                    findField(base.javaClass, "displayGroupId")?.apply {
                        isAccessible = true
                        setInt(base, newGroupId)
                    }
                }
                syncOverrideDisplayGroupId(display, newGroupId)
            }
        }
        log(TAG, "manualMove → group=$newGroupId")
    }

    /**
     * Keep override / cached DisplayInfo on the same PowerGroup as the LogicalDisplay.
     *
     * Never clear [LogicalDisplay.mInfo]: on A16 it is a final DisplayInfoProxy; assigning
     * the field to null NPEs in getDisplayInfoLocked and reboots system_server. Patch
     * displayGroupId in place on override, base, and the proxy's current value instead.
     */
    private fun syncOverrideDisplayGroupId(display: Any, groupId: Int) {
        runCatching {
            var changed = false
            changed = setDisplayGroupIdField(findFieldValue(display, "mOverrideDisplayInfo"), groupId) || changed
            changed = setDisplayGroupIdField(findFieldValue(display, "mBaseDisplayInfo"), groupId) || changed
            val infoObj = findFieldValue(display, "mInfo")
            if (infoObj != null) {
                val cached = findMethod(infoObj.javaClass, "get") { m ->
                    m.parameterTypes.isEmpty()
                }?.invoke(infoObj)
                if (cached != null) {
                    changed = setDisplayGroupIdField(cached, groupId) || changed
                } else if (infoObj.javaClass.name.endsWith("DisplayInfo")) {
                    // Pre-proxy LogicalDisplay: mInfo itself is the DisplayInfo.
                    changed = setDisplayGroupIdField(infoObj, groupId) || changed
                }
            }
            if (changed) {
                log(TAG, "synced displayGroupId → $groupId (in-place, no mInfo clear)")
            }
        }
    }

    private fun setDisplayGroupIdField(info: Any?, groupId: Int): Boolean {
        if (info == null) return false
        val field = findField(info.javaClass, "displayGroupId") ?: return false
        field.isAccessible = true
        if (field.getInt(info) == groupId) return false
        field.setInt(info, groupId)
        return true
    }

    private fun invokeGetDisplay(mapperObj: Any, getDisplay: Method, displayId: Int): Any? {
        return try {
            when (getDisplay.parameterTypes.size) {
                1 -> getDisplay.invoke(mapperObj, displayId)
                else -> getDisplay.invoke(mapperObj, displayId, true)
            }
        } catch (_: Throwable) {
            runCatching { getDisplay.invoke(mapperObj, displayId) }.getOrNull()
        }
    }

    private fun resolveLogicalDisplayMethods(display: Any) {
        if (setDisplayGroupNameLocked == null) {
            setDisplayGroupNameLocked = findMethod(display.javaClass, "setDisplayGroupNameLocked") { m ->
                m.parameterTypes.size == 1 && m.parameterTypes[0] == String::class.java
            }
        }
        if (getDisplayGroupIdLocked == null) {
            getDisplayGroupIdLocked = findMethod(display.javaClass, "getDisplayGroupIdLocked") { m ->
                m.parameterTypes.isEmpty()
            }
        }
        if (updateDisplayGroupIdLocked == null) {
            updateDisplayGroupIdLocked = findMethod(display.javaClass, "updateDisplayGroupIdLocked") { m ->
                m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType
            }
        }
    }

    private fun readGroupId(display: Any): Int {
        runCatching {
            getDisplayGroupIdLocked?.invoke(display)?.let { return it as Int }
        }
        return runCatching {
            findField(display.javaClass, "mDisplayGroupId")?.apply { isAccessible = true }
                ?.getInt(display) ?: -1
        }.getOrDefault(-1)
    }

    private fun findDms(): Any? {
        runCatching {
            val localServices = Class.forName("com.android.server.LocalServices")
            val getService = localServices.getMethod("getService", Class::class.java)
            val internalClass = Class.forName("com.android.server.display.DisplayManagerInternal")
            val internal = getService.invoke(null, internalClass) ?: return@runCatching null
            findFieldValue(internal, "this\$0")
                ?: findFieldValue(internal, "mService")
                ?: findFieldValue(internal, "mDisplayManager")
        }.getOrNull()?.let { return it }

        runCatching {
            val sm = ServiceManager.getService(Context.DISPLAY_SERVICE) ?: return@runCatching null
            var c: Class<*>? = sm.javaClass
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    if (f.name == "this\$0" || f.type.name.contains("DisplayManagerService")) {
                        f.isAccessible = true
                        val v = f.get(sm) ?: continue
                        if (v.javaClass.name.contains("DisplayManagerService")) return v
                    }
                }
                c = c.superclass
            }
            if (findFieldValue(sm, "mLogicalDisplayMapper") != null) return sm
            null
        }.getOrNull()?.let { return it }

        return null
    }

    private fun findFieldValue(obj: Any, name: String): Any? {
        return findField(obj.javaClass, name)?.let { f ->
            f.isAccessible = true
            f.get(obj)
        }
    }

    private fun findField(clazz: Class<*>, name: String): Field? {
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            runCatching { c!!.getDeclaredField(name) }.getOrNull()?.let { return it }
            c = c.superclass
        }
        return null
    }

    private fun findMethod(
        clazz: Class<*>,
        name: String,
        pred: (Method) -> Boolean,
    ): Method? {
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) {
                if (m.name == name && pred(m)) {
                    m.isAccessible = true
                    return m
                }
            }
            c = c.superclass
        }
        return null
    }
}
