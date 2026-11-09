package io.github.nitsuya.aa.display.xposed;

import android.view.MotionEvent;
import android.view.Surface;
import io.github.nitsuya.aa.display.xposed.IVirtualDisplayCreatedListener;
import io.github.nitsuya.aa.display.model.RecentTask;

interface ICoreManager {

    /** Framework module version loaded in system_server; empty when not active. */
    String getVersionName();
    /** Module build time; 0 when the framework side is not loaded (activation sentinel). */
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
    int getFocusedPane();
    /** Swap user task stacks between PRIMARY and SECONDARY panes; ratio follows apps. */
    void swapSplitPanes();
    void onDestroyDisplay();

    void startActivity(String packageName, int userId);
    void startActivityOnPane(String packageName, int userId, int pane);
    void moveTaskToFront(int taskId);
    void removeTask(int taskId);
    void pressKey(int action);
    /**
     * Inject into a pane VD. oneway so AA / :car UI threads are not blocked on
     * InputManager; install + reboot required so system_server Stub matches.
     */
    oneway void touchPane(int pane, in MotionEvent motionEvent);

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
     * Hide the IME on pane VirtualDisplays without bringTaskToFront (which
     * steals IME focus and leaves a stuck keyboard). Appended at end for Stub ordinals.
     */
    oneway void hideIme();
    /**
     * -1 when hidden; PRIMARY(0) / SECONDARY(1) when an IME window is showing on that pane.
     */
    int getImePane();

    /**
     * Recent VD-column tap: synchronous bring-to-front / launch on [pane].
     * Cancels debounced ATMS settle and runs at handler front (same-stack switch
     * must finish before the UI reloads). Prefer this over async [startActivityOnPane].
     */
    boolean startActivityOnPaneForUser(String packageName, int userId, int pane);

    /**
     * Phone BT mouse cursor on AaDisplay shell (pull model).
     * Returns float[6]: visible (0/1), x, y, generation, lockedPeelActive (0/1), lockedPeelRatio.
     */
    float[] getHidCursorOverlay();

    /**
     * Cluster lyric album-art JPEG from system_server memory/disk cache.
     * Empty when unset. Gearhead must not open `/data/system/aadisplay_cluster_art.jpg`
     * (SELinux); use this for HU MediaInfo / getBitmap rewrite.
     */
    byte[] getClusterArtJpeg();

    /** 0 = auto (unset). Valid override: 120..640. Persists + applies live if session active. */
    void setVdDensityDpi(int dpi);
    /** Configured override; 0 when auto/unset. */
    int getVdDensityDpi();
    /** Live session DPI from SplitDisplayController; 0 when no active VD session. */
    int getEffectiveVdDensityDpi();

    /**
     * AADisplay process reports wireless CarPlay Source session liveness.
     * Used so Delay Destroy / egress arbitration can keep VDs while CarPlay is live.
     * Appended at end for Stub ordinals.
     */
    oneway void reportCarPlaySessionLive(boolean live);

    /** True while a CarPlay Source session has reported live. */
    boolean isCarPlaySessionLive();

    /**
     * Armed system_server capture for CarPlay audio egress (media 48 kHz stereo + speech
     * 16 kHz mono rings). Local phone/A2DP sinks are muted while enabled; REMOTE_SUBMIX
     * never uses stream-mute (post-mute mix would be silent) — device-level mute instead.
     * App toggles this when any CarPlay audio stream opens / tears down.
     * Appended at end for Stub ordinals.
     */
    void setCarPlayAudioEgressEnabled(boolean enabled);

    /**
     * Pulls up to [maxBytes] bytes of captured little-endian s16 stereo 48 kHz PCM
     * (CarPlay media / entertainment; empty when disabled or idle); caller byte-swaps
     * to big-endian for the CarPlay wire. Returned as the result because AIDL `out byte[]`
     * rebinds a fresh array in the generated proxy.
     * Appended at end for Stub ordinals.
     */
    byte[] pullCarPlayAudio(int maxBytes);

    /**
     * Injects a scroll at pane-space (x, y) — CarPlay knob wheel on the focused pane.
     * Appended at end for Stub ordinals.
     */
    oneway void scrollPane(int pane, int x, int y, int vScroll, int hScroll);

    /**
     * Pulls up to [maxBytes] bytes of captured little-endian s16 mono 16 kHz PCM for the
     * CarPlay speech / navigation channel (empty when disabled or idle).
     * Appended at end for Stub ordinals.
     */
    byte[] pullCarPlaySpeechAudio(int maxBytes);

    /**
     * True when an exclusive nav/assistant AudioPolicy tap is armed. Speech SETUP must
     * not open otherwise — an empty/bleed speech stream mixed with media on DiPlay is
     * heard as echo. Appended at end for Stub ordinals.
     */
    boolean isCarPlaySpeechCaptureReady();

    /**
     * Turns the Wi-Fi radio on or off from system_server. A normal app
     * setWifiEnabled() is rejected on API 29+, so Audi handoff cannot scan
     * while the radio is off. Appended at end for Stub ordinals.
     */
    void setWifiEnabled(boolean enabled);

    /**
     * MAC of the Wi-Fi STA actually associated (the address the accessory AP
     * sees). Apps only receive 02:00:00:00:00:00. Empty when Wi-Fi is down.
     * Appended at end for Stub ordinals.
     */
    String localWifiMac();

    /**
     * Public Bluetooth address the accessory sees on the ACL. Apps only receive
     * 02:00:00:00:00:00. Empty when the adapter has no address.
     * Appended at end for Stub ordinals.
     */
    String localBluetoothMac();

    /**
     * Register a CarPlay head-unit MAC: confirm any in-flight SSP pairing
     * (app lacks BLUETOOTH_PRIVILEGED), forbid A2DP (timeout → "cannot establish
     * Bluetooth"), and reject PBAP/MAP so contacts dialogs do not appear.
     * Appended at end for Stub ordinals.
     */
    void prepareCarPlayHeadUnit(String address);

    /**
     * Car hotspot is up but has no internet. Clear ACCEPT_UNVALIDATED so cellular
     * becomes the system default for INTERNET while the Wi‑Fi LAN stays associated.
     * Appended at end for Stub ordinals; requires system_server reload (reboot).
     */
    void preferCellularOverUnvalidatedWifi();

    /**
     * Make the adapter CONNECTABLE_DISCOVERABLE from system_server (has
     * BLUETOOTH_PRIVILEGED). App uid setScanMode always fails. Appended at end
     * for Stub ordinals; requires system_server reload (reboot).
     */
    void armBluetoothDiscoverable();

    /**
     * Host Recents / app-picker visibility → phone BT mouse shell-capture gate.
     * True while either overlay is open so HID clicks route to Host chrome
     * (AaDisplay presentation retired). Appended at end for Stub ordinals.
     */
    oneway void reportHidShellOverlay(boolean open);

    /**
     * Drain queued phone-BT shell touches (normalized 0..1) for Host Recents /
     * picker. Flat triplets: action, xNorm, yNorm, … Empty when idle.
     * Appended at end for Stub ordinals; requires system_server reload (reboot).
     */
    float[] drainHidShellTouches();
}
