package io.github.nitsuya.aa.display.xposed;

import android.view.MotionEvent;
import android.view.Surface;
import io.github.nitsuya.aa.display.xposed.IVirtualDisplayCreatedListener;
import io.github.nitsuya.aa.display.model.RecentTask;

interface ICoreManager {

    String getVersionName();
    long getBuildTime();

    /** Create dual-pane virtual displays for custom split. [ratio] is primary fraction (0.2..0.8). */
    void onCreateSplitDisplay(
        int width,
        int height,
        int densityDpi,
        float ratio,
        in Surface primarySurface,
        in Surface secondarySurface,
        IVirtualDisplayCreatedListener listener
    );
    void setPaneSurface(int pane, in Surface surface);
    void setSplitRatio(float ratio);
    float getSplitRatio();
    /** Package currently owned by [pane], or null/empty when vacant. */
    String getPanePackage(int pane);
    void setFocusedPane(int pane);
    int getFocusedPane();
    /** Swap user task stacks between PRIMARY and SECONDARY panes; ratio follows apps. */
    void swapSplitPanes();
    void onDestroyDisplay();

    void startActivity(String packageName, int userId);
    void startActivityOnPane(String packageName, int userId, int pane);
    void moveTaskId(int taskId, boolean isVirtualDisplay);
    /** Move task onto PRIMARY/SECONDARY virtual-display pane (not the phone). */
    void moveTaskIdToPane(int taskId, int pane);
    void moveTaskToFront(int taskId);
    void moveSecondTaskToFront();
    void removeTask(int taskId);
    void pressKey(int action);
    /**
     * Inject into a pane VD. oneway so AA / :car UI threads are not blocked on
     * InputManager; install + reboot required so system_server Stub matches.
     */
    oneway void touchPane(int pane, in MotionEvent motionEvent);
    /**
     * Relay Coolwalk left-rail HU touches from :car into the PRIMARY pane virtual display.
     */
    oneway void touchPrimaryPane(in MotionEvent motionEvent);

    RecentTask getRecentTask();

    /**
     * Enter fullscreen for PRIMARY(0)/SECONDARY(1), or exit with -1.
     * Both virtual displays stay full-size; AA UI shows one and stacks the other.
     * Appended at end so older system_server stubs keep prior transaction ordinals.
     */
    void setSplitFullscreen(int pane);
    /** -1 when split; PRIMARY(0) or SECONDARY(1) when one pane is fullscreen. */
    int getSplitFullscreenPane();

    /**
     * Inject into the AaDisplayActivity presentation display (peel handle / AA UI),
     * not a pane VirtualDisplay. Used when Coolwalk rail steal would otherwise bury
     * flush-left peel touches into the fullscreen app. Appended at end for Stub ordinals.
     */
    oneway void touchAaDisplay(in MotionEvent motionEvent);

    /**
     * AaDisplayActivity's presentation VirtualDisplay is FLAG_PRIVATE (app-owned), so
     * system_server DisplayManager cannot enumerate it. The AA UI process reports the
     * live displayId so [touchAaDisplay] can inject. Pass INVALID_DISPLAY (-1) to clear.
     * Appended at end for Stub ordinals.
     */
    oneway void reportAaUiDisplayId(int displayId);

    /**
     * Hide the IME on pane VirtualDisplays without bringTaskToFront (which
     * steals IME focus and leaves a stuck keyboard). Appended at end for Stub ordinals.
     */
    oneway void hideIme();
    /**
     * -1 when hidden; PRIMARY(0) / SECONDARY(1) when an IME window is showing on that pane.
     */
    int getImePane();

    /**
     * gearhead :projection / :car report Coolwalk rail snapshot for display-profile settle.
     * [phase] is [RailPhase.code]. Appended at end for Stub ordinals.
     */
    oneway void reportCoolwalkRailSnapshot(int phase, int touchRailWidthPx, int fullHuWidthPx, int facetDisplayId);

    /**
     * Returns int[4]: phase, touchRailWidthPx, fullHuWidthPx, facetDisplayId.
     */
    int[] getCoolwalkRailSnapshot();

    /**
     * system_server → AADisplay: gearhead cannot reliably deliver package-targeted
     * broadcasts on Android 13+; relay through system uid like [ACTION_REQUEST_DISPLAY_RECOVERY].
     */
    oneway void notifyCoolwalkFullBleed();
}
