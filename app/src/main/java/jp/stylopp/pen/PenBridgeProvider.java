package jp.stylopp.pen;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IInterface;
import android.os.Process;
import java.io.File;
import java.util.concurrent.TimeUnit;

/** Gives our root helper the registered caller token that a standalone app_process lacks. */
public final class PenBridgeProvider extends ContentProvider {
    public static final String AUTHORITY = "jp.stylopp.pen.bridge";
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (Binder.getCallingUid() != 0 && Binder.getCallingUid() != Process.myUid())
            throw new SecurityException("Only Stylo++ and its root helper may access the pen bridge");
        if ("diagnose".equals(method)) return diagnose();
        if (!"caller".equals(method)) throw new IllegalArgumentException("Unsupported operation");
        Bundle result = new Bundle();
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
            IInterface application = (IInterface) activityThread.getMethod("getApplicationThread").invoke(thread);
            result.putBinder("caller", application.asBinder());
            result.putString("package", getContext().getPackageName());
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Caller token unavailable", e); }
        return result;
    }

    /** Runs only getters, from the actual app UID and mount namespace, for support diagnostics. */
    private Bundle diagnose() {
        Bundle result = new Bundle();
        try {
            String apk = getContext().getApplicationInfo().sourceDir.replace("'", "'\\''");
            String su = new File("/system/bin/su").canExecute() ? "/system/bin/su" : "su";
            java.lang.Process process = new ProcessBuilder(su, "-c", "CLASSPATH='" + apk + "' app_process /system/bin jp.stylopp.pen.RootPenService --probe")
                .redirectErrorStream(true).start();
            process.getOutputStream().close();
            if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroy(); result.putString("result", "PROBE_TIMEOUT"); return result; }
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while (output.size() < 8192 && (count = process.getInputStream().read(buffer, 0, Math.min(buffer.length, 8192 - output.size()))) > 0) output.write(buffer, 0, count);
            result.putString("result", output.toString("UTF-8"));
            result.putInt("exit", process.exitValue());
        } catch (Exception e) { result.putString("result", e.getClass().getSimpleName() + ": " + e.getMessage()); }
        return result;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { throw new UnsupportedOperationException(); }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
