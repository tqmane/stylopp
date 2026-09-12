package jp.stylopp;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedModule;

/** libxposed API 102 only. No legacy XposedBridge API or system-server hooks. */
public final class StyloppModule extends XposedModule {
    private WeakHashMap<Activity, HostPenClient> clients;
    private SharedPreferences preferences;
    private final java.util.HashSet<java.lang.reflect.Method> inputMethods = new java.util.HashSet<>();
    private Handler ui;
    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        if (param.isSystemServer()) return;
        clients = new WeakHashMap<>(); ui = new Handler(Looper.getMainLooper());
        try {
            preferences = getRemotePreferences("stylopp");
            preferences.registerOnSharedPreferenceChangeListener((p, key) -> ui.post(() -> {
                for (HostPenClient client : clients.values().toArray(new HostPenClient[0])) client.updateOptions();
            }));
            for (String method : new String[]{"onResume", "onPostResume"}) {
                hook(Activity.class.getDeclaredMethod(method)).setId("stylopp." + method).intercept(chain -> {
                    Object result = chain.proceed();
                    try { attach((Activity) chain.getThisObject()); } catch (Throwable error) { log(Log.WARN, "Stylopp", "Cannot attach pen tools", error); }
                    return result;
                });
            }
            hook(Activity.class.getDeclaredMethod("onPause")).setId("stylopp.pause").intercept(chain -> {
                try { HostPenClient client = clients.get((Activity) chain.getThisObject()); if (client != null) client.pause(); } catch (Throwable ignored) { }
                return chain.proceed();
            });
            hook(Activity.class.getDeclaredMethod("onDestroy")).setId("stylopp.destroy").intercept(chain -> {
                try { HostPenClient client = clients.remove((Activity) chain.getThisObject()); if (client != null) client.dispose(); } catch (Throwable ignored) { }
                return chain.proceed();
            });
            log(Log.INFO, "Stylopp", "Pen hooks registered with API " + getApiVersion());
        } catch (Throwable error) { log(Log.ERROR, "Stylopp", "Module initialization failed", error); }
    }
    private void attach(Activity activity) throws NoSuchMethodException {
        String pkg = activity.getPackageName();
        if (pkg.equals("jp.stylopp") || pkg.equals("android")) return;
        HostPenClient client = clients.get(activity);
        if (client == null) { client = new HostPenClient(activity, preferences); clients.put(activity, client); }
        installInputHook(activity.getClass(), "dispatchTouchEvent", android.view.MotionEvent.class);
        installInputHook(activity.getClass(), "dispatchGenericMotionEvent", android.view.MotionEvent.class);
        installInputHook(activity.getClass(), "onWindowFocusChanged", boolean.class);
        client.resume();
    }
    private void installInputHook(Class<?> type, String name, Class<?> parameter) throws NoSuchMethodException {
        java.lang.reflect.Method method = type.getMethod(name, parameter);
        if (!inputMethods.add(method)) return;
        hook(method).setId("stylopp." + name).intercept(chain -> {
            try {
                HostPenClient client = clients.get((Activity) chain.getThisObject());
                if (client != null) {
                    if (name.equals("dispatchTouchEvent")) client.observe((android.view.MotionEvent) chain.getArg(0));
                    else if (name.equals("dispatchGenericMotionEvent")) client.hover((android.view.MotionEvent) chain.getArg(0));
                    else if (!(Boolean) chain.getArg(0)) client.loseFocus();
                }
            } catch (RuntimeException ignored) { }
            return chain.proceed();
        });
    }
}
