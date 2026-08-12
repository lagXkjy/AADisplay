package io.github.nitsuya.aa.display.ui.aa.split

import io.github.nitsuya.aa.display.BuildConfig

/**
 * Packages treated as VD chrome / Secondary HOME (not user apps).
 * Includes Samsung launcher / AppsEdge for empty-pane detection — not OneUI StageCoordinator.
 */
internal object SplitChromePackages {
    val BOUNCE_EXCLUDED: Set<String> = setOf(
        BuildConfig.APPLICATION_ID,
        "android",
        "com.android.systemui",
        "com.android.launcher3",
        "com.sec.android.app.launcher",
        "com.samsung.android.app.appsedge",
    )
}
