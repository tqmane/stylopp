package jp.stylopp;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;
import java.util.HashSet;

public final class StyloppApp extends Application implements XposedServiceHelper.OnServiceListener {
    public static volatile XposedService service;
    public static SharedPreferences preferences(Context context) { return context.getSharedPreferences("stylopp", MODE_PRIVATE); }
    @Override public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(this);
        if (preferences(this).getBoolean("enabled", false)) RootPenController.get(this).connect();
    }
    @Override public void onServiceBind(XposedService value) { service = value; try { publish(this); } catch (RuntimeException ignored) { } }
    @Override public void onServiceDied(XposedService value) { if (service == value) service = null; }
    public static void publish(Context context) {
        XposedService current = service; if (current == null) return;
        SharedPreferences p = preferences(context);
        current.getRemotePreferences("stylopp").edit()
            .putBoolean("enabled", p.getBoolean("enabled", false))
            .putBoolean("vibration", p.getBoolean("vibration", true))
            .putBoolean("overlay", p.getBoolean("overlay", true))
            .putBoolean("cursor", p.getBoolean("cursor", true))
            .putInt("style", p.getInt("style", 3))
            .putStringSet("apps", new HashSet<>(p.getStringSet("apps", java.util.Collections.emptySet()))).apply();
    }
}
