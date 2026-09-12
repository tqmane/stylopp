package jp.stylopp.pen;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.util.HashSet;
import java.util.Set;

/** Protocol checked against the installed IPeManager 16.12.2 APK, not a generic OEM guess. */
public final class OplusPenProtocol {
    public static final String DESCRIPTOR = "com.oplus.ipemanager.sdk.ISdkAidlInterface";
    public static final String APK_SHA256 = "661869775396ac66b1b048aaaae3ecbc3079906cc5884ac768eab98760db5cad";
    public static final int SDK_VERSION = 20100;
    public static final long PACKAGE_VERSION = 16120002L;
    public static final int FUNCTION_FEEDBACK = 4;
    private final IBinder binder;

    public OplusPenProtocol(IBinder binder) throws RemoteException {
        if (!DESCRIPTOR.equals(binder.getInterfaceDescriptor())) throw new SecurityException("Descriptor mismatch");
        this.binder = binder;
    }

    public int readInt(int transaction) throws RemoteException {
        if (transaction != 2 && transaction != 3 && transaction != 6 && transaction != 9)
            throw new IllegalArgumentException("Unsupported getter");
        Parcel request = Parcel.obtain(), reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(DESCRIPTOR);
            if (!binder.transact(transaction, request, reply, 0)) throw new RemoteException("Unsupported getter");
            reply.readException();
            return reply.readInt();
        } finally { reply.recycle(); request.recycle(); }
    }

    public Set<Integer> features() throws RemoteException {
        Parcel request = Parcel.obtain(), reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(DESCRIPTOR);
            if (!binder.transact(7, request, reply, 0)) throw new RemoteException("Unsupported features");
            reply.readException();
            int count = reply.readInt();
            if (count < 0 || count > 3) throw new SecurityException("Unknown feature schema");
            Set<Integer> features = new HashSet<>();
            for (int i = 0; i < count; i++) {
                if (reply.readInt() != 1) throw new SecurityException("Null feature");
                int value = reply.readInt(); // IpeFeature.writeToParcel writes its enum ordinal.
                if (value < 0 || value > 2) throw new SecurityException("Unknown feature");
                features.add(value);
            }
            return features;
        } finally { reply.recycle(); request.recycle(); }
    }

    public boolean markerSupported() throws RemoteException {
        Parcel request = Parcel.obtain(), reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(DESCRIPTOR);
            request.writeString("marker_pen_vibration");
            if (!binder.transact(13, request, reply, 0)) return false;
            reply.readException();
            return reply.readInt() != 0;
        } finally { reply.recycle(); request.recycle(); }
    }

    public void send(int transaction, Integer value) throws RemoteException {
        if (!((transaction == 4 && value != null && value >= 0 && value <= 4) ||
            (transaction == 5 && value != null && value == FUNCTION_FEEDBACK) ||
            ((transaction == 11 || transaction == 12) && value == null)))
            throw new IllegalArgumentException("Unsupported operation");
        Parcel request = Parcel.obtain();
        try {
            request.writeInterfaceToken(DESCRIPTOR);
            if (value != null) request.writeInt(value);
            // Verified in k3.c: setters and start/stop are ONEWAY, without a reply Parcel.
            if (!binder.transact(transaction, request, null, IBinder.FLAG_ONEWAY)) throw new RemoteException("Unsupported operation");
        } finally { request.recycle(); }
    }
}
