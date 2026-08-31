package android.hardware.display;

/**
 * Hidden {@link DisplayManager} VirtualDisplay flag bits used by dual-VD creation.
 * Values match AOSP DisplayManager (API 31–36); keep named rather than magic bitshifts.
 */
public final class DisplayManagerHidden {

    private DisplayManagerHidden() {
    }

    /**
     * @hide Indicates that the display should be trusted.
     */
    public static final int VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 << 10;

    /**
     * @hide Indicates that the virtual display should own its display group.
     */
    public static final int VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 << 11;

    /**
     * @hide Indicates that the virtual display should be always unlocked.
     */
    public static final int VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 << 12;

    /**
     * @hide Disables touch feedback (haptics / sound) on the virtual display.
     */
    public static final int VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED = 1 << 13;

    /**
     * @hide Register with InputFlinger so the display gets a touch/pointer viewport
     * (needed for {@code setVirtualMousePointerDisplayId} to paint the cursor there).
     */
    public static final int VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 << 6;

    /**
     * @hide Virtual display should support system decorations (status/nav/launcher).
     * AADisplay pane VDs must never set this — see SplitVdLifecycle.vdFlags().
     * On Android 16+ this create-time flag replaces IWindowManager#setShouldShowSystemDecors.
     */
    public static final int VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 << 9;

    /**
     * @hide Display maintains its own focus and touch mode (per-display focus).
     * Required so pane apps can keep a focused window while the phone remains
     * the top focused display for untargeted / handset input.
     */
    public static final int VIRTUAL_DISPLAY_FLAG_OWN_FOCUS = 1 << 14;
}
