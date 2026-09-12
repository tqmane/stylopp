package jp.stylopp.pen;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Binder;
import android.os.Bundle;
import android.content.AttributionSource;
import java.lang.reflect.InvocationTargetException;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.List;

/** Fixed, private stdin/stdout protocol. Runs only via an explicitly enabled su session. */
public final class RootPenService {
    private static final String PACKAGE = "com.oplus.ipemanager";
    private static final String DESCRIPTOR = PACKAGE + ".sdk.ISdkAidlInterface";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Context context;
    private OplusPenProtocol api;
    private boolean bound, writing, closed;
    private boolean probe;
    private String stage = "CONTEXT_INIT";
    private Object activityManager;
    private Class<?> activityManagerInterface;
    private final IBinder providerToken = new Binder();
    private boolean providerHeld;
    private long lastKeepAlive, lastPulse, lastConnectionCheck;

    public static void main(String[] args) {
        if (android.os.Build.VERSION.SDK_INT < 31) { System.out.println("ERROR PROTOCOL_UNSUPPORTED"); return; }
        if (android.os.Process.myUid() != 0 || (args.length != 0 && !(args.length == 1 && args[0].equals("--probe")))) {
            System.out.println("ERROR ROOT_REQUIRED");
            return;
        }
        Looper.prepareMainLooper();
        RootPenService service = new RootPenService();
        service.probe = args.length == 1;
        Runtime.getRuntime().addShutdownHook(new Thread(service::stopSafely));
        service.connect();
        Looper.loop();
    }

    private void connect() {
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Object main = thread.getMethod("systemMain").invoke(null);
            context = (Context) thread.getMethod("getSystemContext").invoke(main);
            stage = "PACKAGE_LOOKUP";
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(PACKAGE, 0);
            // Only inspect the installed system package, never a path supplied over the pipe.
            if ((info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0)
                throw new SecurityException("Not a system package");
            long version = context.getPackageManager().getPackageInfo(PACKAGE, 0).getLongVersionCode();
            stage = "PROFILE_CHECK";
            if (version != OplusPenProtocol.PACKAGE_VERSION || !OplusPenProtocol.APK_SHA256.equals(sha256(info.sourceDir))) {
                shutdown("PROTOCOL_UNSUPPORTED");
                return;
            }
            stage = "BIND_FAILED";
            bound = bindWithAppCaller();
            if (!bound) shutdown("BIND_FAILED");
            handler.postDelayed(() -> { if (api == null && !closed) shutdown("BIND_TIMEOUT"); }, 8000);
        } catch (Throwable e) { reportException(stage, e); shutdown(stage); }
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (closed) return;
            try {
                if (!DESCRIPTOR.equals(binder.getInterfaceDescriptor())) throw new SecurityException();
                api = new OplusPenProtocol(binder);
                int version = api.readInt(6);
                if (version != OplusPenProtocol.SDK_VERSION) throw new IllegalStateException();
                int featureCount = api.features().size();
                if (probe) {
                    System.out.println("PROBE sdk=" + version + " features=" + featureCount + " connection=" + api.readInt(3) + " writing=" + api.readInt(2) + " tap=" + api.readInt(9));
                    System.out.flush();
                    shutdown(null);
                    return;
                }
                binder.linkToDeath(() -> handler.post(() -> shutdown("DISCONNECTED")), 0);
                System.out.println("READY " + version + " " + featureCount);
                System.out.flush();
                startReader();
                handler.post(watchdog);
            } catch (Throwable e) { reportException("PROTOCOL_CHECK", e); shutdown("PROTOCOL_CHECK"); }
        }
        @Override public void onServiceDisconnected(ComponentName name) { shutdown("DISCONNECTED"); }
        @Override public void onBindingDied(ComponentName name) { shutdown("DISCONNECTED"); }
        @Override public void onNullBinding(ComponentName name) { shutdown("BIND_FAILED"); }
    };

    @android.annotation.TargetApi(31)
    private boolean bindWithAppCaller() throws Exception {
        activityManagerInterface = Class.forName("android.app.IActivityManager");
        activityManager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        stage = "CALLER_BRIDGE";
        Object holder = activityManagerInterface.getMethod("getContentProviderExternal", String.class, int.class, IBinder.class, String.class)
            .invoke(activityManager, PenBridgeProvider.AUTHORITY, 0, providerToken, "stylopp-pen");
        if (holder == null) throw new IllegalStateException("Stylo++ provider is unavailable");
        providerHeld = true;
        Object provider = holder.getClass().getField("provider").get(holder);
        Class<?> providerInterface = Class.forName("android.content.IContentProvider");
        Bundle reply = (Bundle) providerInterface.getMethod("call", AttributionSource.class, String.class, String.class, String.class, Bundle.class)
            .invoke(provider, new AttributionSource.Builder(0).setPackageName("android").build(), PenBridgeProvider.AUTHORITY, "caller", null, null);
        IBinder caller = reply.getBinder("caller");
        if (caller == null || !"jp.stylopp".equals(reply.getString("package"))) throw new SecurityException("Invalid caller bridge");
        Class<?> applicationThread = Class.forName("android.app.IApplicationThread");
        Object registeredCaller = Class.forName("android.app.IApplicationThread$Stub").getMethod("asInterface", IBinder.class).invoke(null, caller);
        Object dispatcher = context.getClass().getMethod("getServiceDispatcher", ServiceConnection.class, Handler.class, long.class)
            .invoke(context, connection, handler, (long) Context.BIND_AUTO_CREATE);
        stage = "BIND_FAILED";
        Object result = activityManagerInterface.getMethod("bindServiceInstance", applicationThread, IBinder.class, Intent.class, String.class,
                Class.forName("android.app.IServiceConnection"), long.class, String.class, String.class, int.class)
            .invoke(activityManager, registeredCaller, null,
                new Intent("com.oplus.ipemanager.ACTION.PENCIL_SDK").setPackage(PACKAGE), null, dispatcher,
                (long) Context.BIND_AUTO_CREATE, null, "jp.stylopp", 0);
        return ((Integer) result) > 0;
    }

    private static String sha256(String path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(path)) {
            byte[] buffer = new byte[65536];
            int length;
            while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value));
        return result.toString();
    }

    private static void reportException(String stage, Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
        System.err.println("NOTIE_PEN_ERROR " + stage + " " + error.getClass().getSimpleName() + ": " + error.getMessage());
    }

    private void startReader() {
        Thread reader = new Thread(() -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = input.readLine()) != null) {
                    if (line.length() > 12) break;
                    final String command = line;
                    handler.post(() -> command(command));
                }
            } catch (Exception ignored) { }
            handler.post(() -> shutdown(null));
        }, "stylopp-pen-pipe");
        reader.setDaemon(true);
        reader.start();
    }

    private void command(String command) {
        if (closed || api == null) return;
        try {
            switch (command) {
                case "W0": start(0); break; // Pencil
                case "W1": start(1); break; // Eraser
                case "W2": start(2); break; // Ballpoint
                case "W3": start(3); break; // Fountain pen
                case "W4": start(4); break; // Highlighter
                case "KEEP": if (writing) lastKeepAlive = SystemClock.elapsedRealtime(); break;
                case "STOP": stopSafely(); break;
                case "PULSE":
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastPulse >= 80 && api.readInt(9) != 0 && api.readInt(3) == 2 && api.features().contains(1)) {
                        api.send(5, OplusPenProtocol.FUNCTION_FEEDBACK); // FUNCTION_VIBRATION enum ordinal; NOT tool code 5.
                        lastPulse = now;
                    }
                    break;
                case "QUIT": shutdown(null); break;
                default: shutdown("INVALID_COMMAND");
            }
        } catch (Throwable e) { shutdown("TRANSACTION_FAILED"); }
    }

    private void start(int type) throws Exception {
        stopSafely();
        if (api.readInt(3) != 2) { reportBlocked("PEN_DISCONNECTED"); return; }
        if (api.readInt(2) == 0 || !api.features().contains(2)) { reportBlocked("WRITING_DISABLED"); return; }
        if (type == 4 && !api.markerSupported()) return;
        api.send(4, type);
        // Mark first, so a partially successful start still gets a stop on failure.
        writing = true;
        lastKeepAlive = SystemClock.elapsedRealtime();
        api.send(11, null);
    }

    private void reportBlocked(String reason) { System.out.println("BLOCKED " + reason); System.out.flush(); }

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (closed) return;
            if (writing && SystemClock.elapsedRealtime() - lastKeepAlive > 1000) stopSafely();
            if (writing && SystemClock.elapsedRealtime() - lastConnectionCheck > 1000) {
                lastConnectionCheck = SystemClock.elapsedRealtime();
                try { if (api.readInt(3) != 2 || api.readInt(2) == 0) stopSafely(); }
                catch (Exception e) { shutdown("DISCONNECTED"); return; }
            }
            handler.postDelayed(this, 200);
        }
    };

    private synchronized void stopSafely() {
        if (!writing) return;
        writing = false;
        try { api.send(12, null); } catch (Throwable ignored) { }
    }

    private void shutdown(String error) {
        if (closed) return;
        closed = true;
        stopSafely();
        handler.removeCallbacksAndMessages(null);
        if (bound) { try { context.unbindService(connection); } catch (Exception ignored) { } }
        if (providerHeld) {
            try { activityManagerInterface.getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class, int.class)
                .invoke(activityManager, PenBridgeProvider.AUTHORITY, providerToken, 0); } catch (Exception ignored) { }
        }
        if (error != null) { System.out.println("ERROR " + error); System.out.flush(); }
        System.exit(error == null ? 0 : 1);
    }
}
