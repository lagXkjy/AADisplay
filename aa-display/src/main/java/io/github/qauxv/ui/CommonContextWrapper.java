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

package io.github.qauxv.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.view.ContextThemeWrapper;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.github.kyuubiran.ezxhelper.init.EzXHelperInit;

import io.github.qauxv.util.SavedInstanceStatePatchedClassReferencer;

/**
 * If you just want to create a MaterialDialog or AppCompatDialog, see {@link #createAppCompatContext(Context)}.
 **/
public class CommonContextWrapper extends ContextThemeWrapper {

    /**
     * Creates a new context wrapper with the specified theme with correct module ClassLoader.
     *
     * @param base  the base context
     * @param theme the resource ID of the theme to be applied on top of the base context's theme
     */
    public CommonContextWrapper(@NonNull Context base, int theme) {
        super(base, theme);
        EzXHelperInit.INSTANCE.addModuleAssetPath(getResources());
    }

    private ClassLoader mXref = null;

    @NonNull
    @Override
    public ClassLoader getClassLoader() {
        if (mXref == null) {
            mXref = new SavedInstanceStatePatchedClassReferencer(
                    CommonContextWrapper.class.getClassLoader());
        }
        return mXref;
    }

    public static boolean isAppCompatContext(@NonNull Context context) {
        if (!checkContextClassLoader(context)) {
            return false;
        }
        TypedArray a = context.obtainStyledAttributes(androidx.appcompat.R.styleable.AppCompatTheme);
        try {
            return a.hasValue(androidx.appcompat.R.styleable.AppCompatTheme_windowActionBar);
        } finally {
            a.recycle();
        }
    }

    public static boolean checkContextClassLoader(@NonNull Context context) {
        try {
            ClassLoader cl = context.getClassLoader();
            if (cl == null) {
                return false;
            }
            return cl.loadClass(AppCompatActivity.class.getName()) == AppCompatActivity.class;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @NonNull
    public static Context createAppCompatContext(@NonNull Context base) {
        if (isAppCompatContext(base)) {
            return base;
        }
        return new CommonContextWrapper(base, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar);
    }
}
