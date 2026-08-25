package android.view;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

/** @hide compile-only stub — runtime resolves to framework. */
public interface IInputFilterHost extends IInterface {
    void sendInputEvent(InputEvent event, int policyFlags) throws RemoteException;

    abstract class Stub extends Binder implements IInputFilterHost {
        public static IInputFilterHost asInterface(IBinder obj) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IBinder asBinder() {
            return this;
        }
    }
}
