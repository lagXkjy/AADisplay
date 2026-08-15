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
}
