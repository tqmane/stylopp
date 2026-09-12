package jp.stylopp;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ContentProviderController;
import org.robolectric.annotation.Config;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowBinder;
import org.robolectric.Robolectric;
import java.util.Collections;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class BridgeTest {
    @Test public void compressedSnapshotsRejectCorruptionAndExpansionBombs() throws Exception {
        String json = "[{\"points\":[[0.1,0.2,0.5]],\"color\":-1,\"width\":2}]";
        assertEquals(json, InputBridge.unpack(InputBridge.pack(json)));
        assertThrows(IllegalArgumentException.class, () -> InputBridge.unpack(new byte[]{1,2,3}));
        assertThrows(IllegalArgumentException.class, () -> InputBridge.pack("x".repeat(InputBridge.MAX_DATA + 1)));
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream zip = new java.util.zip.GZIPOutputStream(bytes)) { zip.write(new byte[InputBridge.MAX_DATA + 1]); }
        assertThrows(IllegalArgumentException.class, () -> InputBridge.unpack(bytes.toByteArray()));
    }
    @Test public void penEndpointRequiresSelectedCallingUidAndNeverExposesShell() throws Exception {
        android.app.Application app = RuntimeEnvironment.getApplication();
        PackageInfo info = new PackageInfo(); info.packageName = "jp.stylopp.demo"; info.applicationInfo = new ApplicationInfo(); info.applicationInfo.packageName = info.packageName; info.applicationInfo.uid = 10177;
        Shadows.shadowOf(app.getPackageManager()).installPackage(info);
        StyloppApp.preferences(app).edit().putBoolean("enabled", true).putBoolean("vibration", false).putStringSet("apps", Collections.singleton(info.packageName)).commit();
        InputBridge bridge = Robolectric.buildContentProvider(InputBridge.class).create().get();
        ShadowBinder.setCallingUid(10288);
        assertThrows(SecurityException.class, () -> bridge.call("open", info.packageName, new Bundle()));
        ShadowBinder.setCallingUid(10177);
        assertThrows(SecurityException.class, () -> bridge.call("shell", info.packageName, new Bundle()));
        Bundle request = new Bundle(); request.putString("document", "42:Demo");
        Bundle result = bridge.call("open", info.packageName, request);
        IBinder input = result.getBinder("input"); assertNotNull(input);
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try { data.writeInterfaceToken(InputBridge.DESCRIPTOR); data.writeByteArray(InputBridge.pack("[]")); input.transact(InputBridge.SAVE, data, reply, 0); reply.readException(); }
        finally { data.recycle(); reply.recycle(); }
        ShadowBinder.setCallingUid(10288);
        Parcel denied = Parcel.obtain(), failure = Parcel.obtain();
        try { denied.writeInterfaceToken(InputBridge.DESCRIPTOR); input.transact(InputBridge.LOAD, denied, failure, 0); assertThrows(SecurityException.class, failure::readException); }
        finally { denied.recycle(); failure.recycle(); ShadowBinder.reset(); }
    }
}
