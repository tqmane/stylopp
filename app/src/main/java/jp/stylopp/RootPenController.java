package jp.stylopp;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.io.*;
import java.util.concurrent.ArrayBlockingQueue;

/** One root connection, leased by one foreground pen gesture at a time. */
final class RootPenController {
    private static RootPenController instance;
    static synchronized RootPenController get(Context context) { if (instance == null) instance = new RootPenController(context.getApplicationContext()); return instance; }
    private final Context context;
    private final Handler timer;
    private volatile Process process;
    private volatile ArrayBlockingQueue<String> queue;
    private volatile boolean connecting, ready;
    private volatile String status = "振動は停止中です";
    private Object owner;
    private volatile long inputCount;
    private int kind = 3;
    private long keepAt;
    private RootPenController(Context context) {
        this.context = context;
        HandlerThread thread = new HandlerThread("stylopp-lease"); thread.start(); timer = new Handler(thread.getLooper());
        timer.post(new Runnable() { @Override public void run() { synchronized (RootPenController.this) {
            if (owner != null && SystemClock.elapsedRealtime() - keepAt > 900) { send("STOP"); owner = null; }
        } timer.postDelayed(this, 250); } });
    }
    String status() { return status + (inputCount > 0 ? " · 筆記入力 " + inputCount + " 回" : ""); }
    boolean ready() { return ready; }
    private boolean enabled() { return StyloppApp.preferences(context).getBoolean("enabled", false) && StyloppApp.preferences(context).getBoolean("vibration", true); }
    synchronized void connect() {
        if (!enabled() || connecting || process != null) return;
        connecting = true; status = "root権限を確認しています…";
        ArrayBlockingQueue<String> commands = new ArrayBlockingQueue<>(64); queue = commands;
        new Thread(() -> {
            Process child = null;
            try {
                String apk = context.getApplicationInfo().sourceDir.replace("'", "'\\''");
                String su = new File("/system/bin/su").canExecute() ? "/system/bin/su" : "su";
                child = new ProcessBuilder(su, "-c", "CLASSPATH='" + apk + "' app_process /system/bin jp.stylopp.pen.RootPenService").redirectErrorStream(true).start();
                final Process running = child;
                synchronized (this) { if (queue != commands || !enabled()) { child.destroy(); return; } process = child; }
                Thread writer = new Thread(() -> { try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(running.getOutputStream()))) {
                    while (true) { String command = commands.take(); out.write(command); out.newLine(); out.flush(); if (command.equals("QUIT")) break; }
                } catch (Exception ignored) { } }, "stylopp-root-writer"); writer.setDaemon(true); writer.start();
                timer.postDelayed(() -> { synchronized (this) { if (process == running && !ready) { status = "root接続がタイムアウトしました。Stylo++への権限を確認してください。"; disconnect(); } } }, 30000);
                try (BufferedReader in = new BufferedReader(new InputStreamReader(child.getInputStream()))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        synchronized (this) {
                            if (process != child) break;
                            if (line.startsWith("READY ")) { ready = true; connecting = false; status = "接続済み · IPeManager SDK 20100"; if (owner != null && SystemClock.elapsedRealtime() - keepAt < 900) send("W" + kind); }
                            else if (line.startsWith("ERROR ") || line.startsWith("BLOCKED ")) status = "ペン接続: " + line.substring(0, Math.min(line.length(), 150));
                        }
                    }
                }
            } catch (Exception e) { status = "suを起動できません。Stylo++にroot権限を許可してください。"; }
            finally { synchronized (this) { if (process == child) { if (!ready && status.startsWith("root権限")) status = "Stylo++へのroot権限を確認して再接続してください。"; process = null; ready = false; connecting = false; owner = null; } } }
        }, "stylopp-root-reader").start();
    }
    private void send(String command) {
        ArrayBlockingQueue<String> commands = queue;
        if (commands != null && !commands.offer(command)) { status = "振動の通信が詰まりました。再接続してください。"; disconnect(); }
    }
    synchronized void start(Object client, int type) {
        if (!enabled() || type < 0 || type > 4) return;
        inputCount++; connect(); owner = client; kind = type; keepAt = SystemClock.elapsedRealtime(); if (ready) { send("STOP"); send("W" + type); }
    }
    synchronized void keep(Object client) { if (owner == client) { keepAt = SystemClock.elapsedRealtime(); if (ready) send("KEEP"); } }
    synchronized void stop(Object client) { if (owner == client) { if (ready) send("STOP"); owner = null; } }
    synchronized void pulseForUserTest() { if (enabled() && ready) send("PULSE"); }
    synchronized void disconnect() {
        ArrayBlockingQueue<String> previous = queue; queue = null; owner = null; ready = false; connecting = false;
        Process old = process; process = null;
        if (previous != null) { previous.clear(); previous.offer("STOP"); previous.offer("QUIT"); }
        if (old != null) timer.postDelayed(old::destroy, 1200);
    }
}
