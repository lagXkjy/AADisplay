package android.view;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

/** @hide compile-only stub — runtime resolves to framework. */
public interface IInputFilter extends IInterface {
    void install(IInputFilterHost host) throws RemoteException;

    void uninstall() throws RemoteException;

    void filterInputEvent(InputEvent event, int policyFlags) throws RemoteException;

    abstract class Stub extends Binder implements IInputFilter {
        public static IInputFilter asInterface(IBinder obj) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IBinder asBinder() {
            return this;
        }
    }
}
