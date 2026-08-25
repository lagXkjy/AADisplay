package android.view;

import android.os.Looper;

/**
 * @hide compile-only stub — runtime resolves to framework {@code android.view.InputFilter}.
 * Subclasses in the module dex load against the bootclasspath implementation.
 */
public abstract class InputFilter extends IInputFilter.Stub {
    public InputFilter(Looper looper) {
        throw new RuntimeException("STUB");
    }

    public void onInputEvent(InputEvent event, int policyFlags) {
    }

    public void onInstalled() {
    }

    public void onUninstalled() {
    }

    protected final void sendInputEvent(InputEvent event, int policyFlags) {
        throw new RuntimeException("STUB");
    }

    @Override
    public void install(IInputFilterHost host) {
        throw new RuntimeException("STUB");
    }

    @Override
    public void uninstall() {
        throw new RuntimeException("STUB");
    }

    @Override
    public void filterInputEvent(InputEvent event, int policyFlags) {
        throw new RuntimeException("STUB");
    }
}
