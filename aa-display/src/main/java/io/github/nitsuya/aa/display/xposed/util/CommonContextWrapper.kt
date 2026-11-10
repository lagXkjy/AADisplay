/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2022 qwq233@qwq2333.top
 * https://github.com/cinit/QAuxiliary
 *
 * This software is non-free but opensource software: you can redistribute it
 * and/or modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version and our eula as published
 * by QAuxiliary contributors.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * and eula along with this software.  If not, see
 * <https://www.gnu.org/licenses/>
 * <https://github.com/cinit/QAuxiliary/blob/master/LICENSE.md>.
 */
package io.github.nitsuya.aa.display.xposed.util

import android.content.Context
import android.view.ContextThemeWrapper
import io.github.nitsuya.aa.display.R

/**
 * Theme + module ClassLoader wrapper for inflating AADisplay layouts in foreign processes.
 */
class CommonContextWrapper(base: Context, theme: Int) : ContextThemeWrapper(base, theme) {

    init {
        ModuleInit.addModuleAssetPath(resources)
    }

    private var mXref: ClassLoader? = null

    override fun getClassLoader(): ClassLoader {
        if (mXref == null) {
            mXref = SavedInstanceStatePatchedClassReferencer(javaClass.classLoader)
        }
        return mXref!!
    }

    companion object {
        /** Prefer existing wrapper; otherwise apply [R.style.Theme_AADisplay]. */
        fun createModuleContext(base: Context): Context {
            if (base is CommonContextWrapper) {
                return base
            }
            return CommonContextWrapper(base, R.style.Theme_AADisplay)
        }
    }
}

/**
 * Bootstrap-first class referencer (QAuxiliary pattern): resolves framework classes via the
 * boot loader, [androidx.lifecycle.ReportFragment] via the host class loader, everything else
 * via the module base loader — keeps `SavedInstanceState`-driven restore compatible when module
 * views are inflated in a foreign process.
 */
class SavedInstanceStatePatchedClassReferencer(referencer: ClassLoader) :
    ClassLoader(Context::class.java.classLoader) {

    private val mBaseReferencer: ClassLoader = referencer
    private val mHostReferencer: ClassLoader? = Initiator.getHostClassLoader()

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        var c: Class<*>? = null
        try {
            c = Context::class.java.classLoader!!.loadClass(name)
        } catch (_: ClassNotFoundException) {
            // fall through
        }
        if (c == null) {
            val host = mHostReferencer
            if (host != null && "androidx.lifecycle.ReportFragment" == name) {
                try {
                    c = host.loadClass(name)
                } catch (_: ClassNotFoundException) {
                    // fall through
                }
            }
        }
        if (c == null) {
            c = mBaseReferencer.loadClass(name)
        }
        return if (resolve) { Class.forName(c.name, true, c.classLoader) } else c
    }
}
