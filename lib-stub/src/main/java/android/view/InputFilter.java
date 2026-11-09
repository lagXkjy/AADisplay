package android.view;

import android.os.Looper;

/**
 * @hide compile-only stub — runtime resolves to framework {@code android.view.InputFilter}.
 * Subclasses in the module dex load against the bootclasspath implementation.
 * Members kept are exactly what the module uses (ctor / onInputEvent / sendInputEvent);
 * install / uninstall are driven via reflection on InputManagerService.
 */
public abstract class InputFilter {
    public InputFilter(Looper looper) {
        throw new RuntimeException("STUB");
    }

    public void onInputEvent(InputEvent event, int policyFlags) {
    }

    protected final void sendInputEvent(InputEvent event, int policyFlags) {
        throw new RuntimeException("STUB");
    }
}
