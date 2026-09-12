package jp.stylopp;

import android.app.Activity;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.FrameLayout;
import android.widget.Toast;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Pen endpoint and overlay state for one Activity; hooks always preserve the original dispatch. */
final class HostPenClient {
    private final Activity activity;
    private final SharedPreferences preferences;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile IBinder endpoint;
    private boolean resumed, disposed, penDown, connecting;
    private int penPointer = -1;
    private PenOverlay overlay;
    HostPenClient(Activity activity, SharedPreferences preferences) { this.activity = activity; this.preferences = preferences; }
    private boolean enabled() { return preferences.getBoolean("enabled", false) && preferences.getStringSet("apps", Collections.emptySet()).contains(activity.getPackageName()); }
    void resume() {
        if (disposed) return; resumed = true;
        updateOptions();
    }
    void updateOptions() {
        if (disposed) return;
        if (!enabled()) { stop(); if (overlay != null) overlay.setEnabledFeatures(false, false); return; }
        if (overlay == null) {
            ViewGroup content = activity.findViewById(android.R.id.content);
            if (content != null) { overlay = new PenOverlay(activity, this::save, this::newSheet); content.addView(overlay, new ViewGroup.LayoutParams(-1, -1)); }
        }
        if (overlay != null) overlay.setEnabledFeatures(preferences.getBoolean("overlay", true), preferences.getBoolean("cursor", true));
        if (!preferences.getBoolean("vibration", true)) stop();
        connect();
    }
    private void connect() {
        if (endpoint != null || connecting || disposed || !enabled()) return;
        connecting = true;
        io.execute(() -> {
            try {
                Bundle request = new Bundle(); request.putString("document", activity.getTaskId() + ":" + activity.getClass().getName());
                Bundle response = activity.getContentResolver().call(Uri.parse("content://" + InputBridge.AUTHORITY), "open", activity.getPackageName(), request);
                if (response == null || response.getInt("version") != 1) throw new IllegalStateException("Input bridge unavailable");
                IBinder binder = response.getBinder("input"); if (binder == null) throw new IllegalStateException("Input bridge unavailable");
                binder.linkToDeath(() -> { endpoint = null; ui.post(this::stop); }, 0);
                endpoint = binder;
                Parcel data = Parcel.obtain(), reply = Parcel.obtain(); String saved;
                try { data.writeInterfaceToken(InputBridge.DESCRIPTOR); if (!binder.transact(InputBridge.LOAD, data, reply, 0)) throw new IllegalStateException(); reply.readException(); saved = InputBridge.unpack(reply.createByteArray()); }
                finally { data.recycle(); reply.recycle(); }
                ui.post(() -> { connecting = false; if (!disposed && overlay != null) overlay.restore(saved); });
            } catch (Exception error) { endpoint = null; ui.post(() -> { connecting = false; if (!disposed && overlay != null) overlay.connectionFailed(); }); }
        });
    }
    void observe(MotionEvent event) {
        if (!resumed || disposed || !enabled() || !preferences.getBoolean("vibration", true)) return;
        int action = event.getActionMasked(); int i = event.getActionIndex();
        boolean pen = event.getToolType(i) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER;
        if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) && pen && !penDown) {
            penDown = true; penPointer = event.getPointerId(i); connect();
            int type = event.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER || (overlay != null && overlay.isErasing()) ? 1 : Math.max(0, Math.min(4, preferences.getInt("style", 3)));
            send(InputBridge.START, type); ui.removeCallbacks(heartbeat); ui.postDelayed(heartbeat, 250);
        } else if (action == MotionEvent.ACTION_CANCEL || ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) && event.getPointerId(i) == penPointer)) stop();
    }
    void hover(MotionEvent event) { if (overlay != null && resumed && enabled()) overlay.hover(event); }
    void loseFocus() { stop(); }
    private final Runnable heartbeat = new Runnable() { @Override public void run() { if (penDown && resumed && enabled()) { send(InputBridge.KEEP, 0); ui.postDelayed(this, 250); } else stop(); } };
    private void send(int command, int value) {
        if (io.isShutdown() || (disposed && command != InputBridge.STOP)) return;
        io.execute(() -> {
            IBinder binder = endpoint; if (binder == null) return;
            Parcel data = Parcel.obtain();
            try { data.writeInterfaceToken(InputBridge.DESCRIPTOR); if (command == InputBridge.START) data.writeInt(value); binder.transact(command, data, null, IBinder.FLAG_ONEWAY); }
            catch (Exception error) { endpoint = null; } finally { data.recycle(); }
        });
    }
    private void writeSnapshot(String json) throws Exception {
        IBinder binder = endpoint; if (binder == null) throw new IllegalStateException();
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try { data.writeInterfaceToken(InputBridge.DESCRIPTOR); data.writeByteArray(InputBridge.pack(json)); if (!binder.transact(InputBridge.SAVE, data, reply, 0)) throw new IllegalStateException(); reply.readException(); }
        finally { data.recycle(); reply.recycle(); }
    }
    private void save(String json) {
        if (io.isShutdown()) return;
        io.execute(() -> { try { writeSnapshot(json); }
            catch (Exception error) { ui.post(() -> Toast.makeText(activity.getApplicationContext(), "筆跡を保存できません。PNGで書き出してください。", Toast.LENGTH_LONG).show()); }
        });
    }
    private void newSheet() {
        if (overlay == null || disposed || io.isShutdown()) return;
        stop(); String json = overlay.content();
        io.execute(() -> {
            try {
                writeSnapshot(json);
                Bundle request = new Bundle(); request.putString("document", activity.getTaskId() + ":" + activity.getClass().getName());
                Bundle response = activity.getContentResolver().call(Uri.parse("content://" + InputBridge.AUTHORITY), "new", activity.getPackageName(), request);
                if (response == null || response.getInt("version") != 1 || response.getBinder("input") == null) throw new IllegalStateException();
                endpoint = response.getBinder("input"); endpoint.linkToDeath(() -> { endpoint = null; ui.post(this::stop); }, 0);
                ui.post(() -> { if (!disposed && overlay != null) overlay.newSheetReady(); });
            } catch (Exception error) { ui.post(() -> Toast.makeText(activity, "今の筆跡を保持しています。接続と空き容量を確認してください。", Toast.LENGTH_LONG).show()); }
        });
    }
    private void stop() { penDown = false; penPointer = -1; ui.removeCallbacks(heartbeat); send(InputBridge.STOP, 0); }
    void pause() { resumed = false; stop(); if (overlay != null) overlay.finish(); }
    void dispose() {
        pause(); disposed = true;
        if (overlay != null && overlay.getParent() instanceof ViewGroup) ((ViewGroup) overlay.getParent()).removeView(overlay);
        overlay = null; io.shutdown();
    }
}
