package android.view;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;

public interface IWindowManager extends IInterface {

    void setShouldShowWithInsecureKeyguard(int displayId, boolean shouldShow);

    void setShouldShowSystemDecors(int displayId, boolean shouldShow);

    void setDisplayImePolicy(int displayId, int imePolicy);

    abstract class Stub extends Binder implements IWindowManager {
        public static IWindowManager asInterface(IBinder obj) {
            throw new UnsupportedOperationException();
        }
    }
}
