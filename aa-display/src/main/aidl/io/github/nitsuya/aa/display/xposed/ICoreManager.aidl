package io.github.nitsuya.aa.display.xposed;

import android.view.Surface;
import android.view.SurfaceControl;
import io.github.nitsuya.aa.display.xposed.IVirtualDisplayCreatedListener;
import io.github.nitsuya.aa.display.model.RecentTask;

interface ICoreManager {

    String getVersionName();
    int getVersionCode();
    int getUid();
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
    void startTaskId(int taskId, String packageName, int userId);
    void moveTaskId(int taskId, boolean isVirtualDisplay);
    /** Move task onto PRIMARY/SECONDARY virtual-display pane (not the phone). */
    void moveTaskIdToPane(int taskId, int pane);
    void moveTaskToFront(int taskId);
    void moveSecondTaskToFront();
    void removeTask(int taskId);
    void restoreLastSplit();
    void pressKey(int action);
    void touchPane(int pane, in MotionEvent motionEvent);
    void toggleDisplayPower();
    void displayPower(boolean displayPower);

    void addMirrorPane(int pane, in SurfaceControl surfaceControl);
    void removeMirrorPane(int pane, in SurfaceControl surfaceControl);

    RecentTask getRecentTask();

    void testCode(String action);
    void toast(String msg);
    void printLog(String tag, String msg);
}
