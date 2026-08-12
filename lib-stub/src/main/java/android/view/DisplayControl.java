package android.view;

import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Hidden Framework display-token helpers (API 34+).
 * Replaces {@code SurfaceControl.getInternalDisplayToken()} which was removed in Android 14.
 */
public class DisplayControl {

    private DisplayControl() {
    }

    @Nullable
    public static long[] getPhysicalDisplayIds() {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    public static IBinder getPhysicalDisplayToken(long physicalDisplayId) {
        throw new RuntimeException("Stub!");
    }
}
