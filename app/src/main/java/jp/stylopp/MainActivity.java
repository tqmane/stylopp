package jp.stylopp;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import io.github.libxposed.service.XposedService;
import java.util.*;

public final class MainActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private SharedPreferences prefs;
    private final ArrayList<ApplicationInfo> apps = new ArrayList<>(), visible = new ArrayList<>();
    private BaseAdapter adapter;
    private final Runnable refresh = new Runnable() { @Override public void run() { refreshStatus(); ui.postDelayed(this, 1000); } };
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); prefs = StyloppApp.preferences(this);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(24), dp(24), dp(24), dp(10)); page.setBackgroundColor(0xff161a20);
        TextView title = text("Stylo++", 30, Color.WHITE); page.addView(title);
        page.addView(text("好きなアプリに、ペンの書き心地を。", 14, 0xffaeb8c8));
        status = text("接続を確認しています…", 12, 0xffb9c9dd); status.setPadding(0, dp(16), 0, dp(12)); page.addView(status);
        toggle(page, "選んだアプリで有効にする", "enabled", false);
        toggle(page, "Stylo 2の筆記振動（root）", "vibration", true);
        toggle(page, "画面に書くツールを表示", "overlay", true);
        toggle(page, "ペンのポインター", "cursor", true);
        LinearLayout actions = new LinearLayout(this);
        Button retry = new Button(this); retry.setText("root再接続"); retry.setOnClickListener(v -> { RootPenController.get(this).disconnect(); RootPenController.get(this).connect(); }); actions.addView(retry);
        Button pulse = new Button(this); pulse.setText("振動を試す"); pulse.setOnClickListener(v -> { if (RootPenController.get(this).ready()) RootPenController.get(this).pulseForUserTest(); else Toast.makeText(this, "Stylo++にroot権限を許可し、接続を確認してください", Toast.LENGTH_LONG).show(); }); actions.addView(pulse);
        Button backup = new Button(this); backup.setText("筆跡バックアップ"); backup.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").putExtra(Intent.EXTRA_TITLE, "stylopp-backup-" + System.currentTimeMillis() + ".zip"), 12)); actions.addView(backup);
        page.addView(actions);
        page.addView(text("画面タップ用の単発振動は送りません。筆跡は画面に固定され、Webページのスクロールには追従しません。", 11, 0xffaeb8c8));
        TextView subtitle = text("対象のアプリ", 19, Color.WHITE); subtitle.setPadding(0, dp(18), 0, dp(10)); page.addView(subtitle);
        EditText search = new EditText(this); search.setSingleLine(true); search.setHint("アプリを検索"); page.addView(search);
        ListView list = new ListView(this); list.setDividerHeight(0); page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        adapter = new BaseAdapter() {
            @Override public int getCount() { return visible.size(); }
            @Override public Object getItem(int position) { return visible.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convert, android.view.ViewGroup parent) {
                ApplicationInfo app = visible.get(position); CheckBox row = new CheckBox(MainActivity.this); row.setPadding(dp(4), dp(11), dp(4), dp(11)); row.setTextColor(Color.WHITE); row.setTextSize(13);
                row.setText(app.loadLabel(getPackageManager()) + "\n" + app.packageName); row.setChecked(selected().contains(app.packageName));
                row.setOnCheckedChangeListener((button, checked) -> select(app.packageName, checked)); return row;
            }
        }; list.setAdapter(adapter);
        search.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s, int start, int count, int after) { } public void onTextChanged(CharSequence s, int start, int before, int count) { filter(s.toString()); } public void afterTextChanged(Editable value) { } });
        page.addView(text("libxposed API 102 · 0.1.0\nVector側でもStylo++と対象アプリを有効にしてください。端末の再起動は行いません。", 10, 0xff8292a8));
        FrameLayout shell = new FrameLayout(this); shell.setBackgroundColor(0xff161a20);
        shell.setOnApplyWindowInsetsListener((view, insets) -> { android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()); view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets; });
        shell.addView(page, new FrameLayout.LayoutParams(Math.min(getResources().getDisplayMetrics().widthPixels, dp(820)), -1, Gravity.CENTER_HORIZONTAL));
        setContentView(shell); shell.requestApplyInsets();
        new Thread(() -> {
            List<ApplicationInfo> installed = getPackageManager().getInstalledApplications(0);
            installed.removeIf(app -> app.packageName.equals(getPackageName()) || app.packageName.equals("android") || app.packageName.equals("com.android.systemui") || getPackageManager().getLaunchIntentForPackage(app.packageName) == null);
            installed.sort(Comparator.comparing(app -> app.loadLabel(getPackageManager()).toString().toLowerCase(Locale.ROOT)));
            ui.post(() -> { apps.addAll(installed); filter(search.getText().toString()); });
        }, "stylopp-apps").start();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 12 || result != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        new Thread(() -> {
            try { try (java.io.OutputStream stream = getContentResolver().openOutputStream(uri); java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(java.util.Objects.requireNonNull(stream))) {
                java.io.File directory = new java.io.File(getFilesDir(), "overlays"); java.io.File[] files = directory.listFiles();
                if (files != null) for (java.io.File file : files) if (file.isFile()) { zip.putNextEntry(new java.util.zip.ZipEntry(file.getName())); try (java.io.InputStream input = new java.io.FileInputStream(file)) { byte[] buffer = new byte[8192]; int n; while ((n = input.read(buffer)) > 0) zip.write(buffer, 0, n); } zip.closeEntry(); }
                zip.putNextEntry(new java.util.zip.ZipEntry("sheets.json")); zip.write(new org.json.JSONObject(getSharedPreferences("sheets", 0).getAll()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
            }
                runOnUiThread(() -> Toast.makeText(this, "筆跡のバックアップを保存しました", Toast.LENGTH_LONG).show());
            } catch (Exception error) { runOnUiThread(() -> Toast.makeText(this, "バックアップを保存できませんでした", Toast.LENGTH_LONG).show()); }
        }, "stylopp-backup").start();
    }
    private TextView text(String value, int size, int color) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view; }
    private void toggle(LinearLayout page, String label, String key, boolean fallback) {
        Switch toggle = new Switch(this); toggle.setText(label); toggle.setTextSize(14); toggle.setTextColor(Color.WHITE); toggle.setPadding(0, dp(10), 0, dp(10)); toggle.setChecked(prefs.getBoolean(key, fallback));
        toggle.setOnCheckedChangeListener((button, checked) -> { prefs.edit().putBoolean(key, checked).apply(); publish(); if (prefs.getBoolean("enabled", false) && prefs.getBoolean("vibration", true)) RootPenController.get(this).connect(); else RootPenController.get(this).disconnect(); }); page.addView(toggle);
    }
    private Set<String> selected() { return new HashSet<>(prefs.getStringSet("apps", Collections.emptySet())); }
    private void select(String pkg, boolean checked) {
        if (!checked) { Set<String> apps = selected(); apps.remove(pkg); prefs.edit().putStringSet("apps", apps).apply(); publish(); return; }
        XposedService service = StyloppApp.service;
        if (service == null) { Toast.makeText(this, "先にVectorのモジュール一覧でStylo++を有効にしてください", Toast.LENGTH_LONG).show(); adapter.notifyDataSetChanged(); return; }
        try {
            if (service.getApiVersion() < 102) { Toast.makeText(this, "libxposed API 102対応のフレームワークが必要です", Toast.LENGTH_LONG).show(); adapter.notifyDataSetChanged(); return; }
            if (service.getScope().contains(pkg)) { approve(pkg); return; }
            service.requestScope(Collections.singletonList(pkg), new XposedService.OnScopeEventListener() {
                @Override public void onScopeRequestApproved(List<String> approved) { ui.post(() -> { if (approved.contains(pkg)) approve(pkg); }); }
                @Override public void onScopeRequestFailed(String message) { ui.post(() -> { Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show(); adapter.notifyDataSetChanged(); }); }
            });
        } catch (RuntimeException error) { Toast.makeText(this, "対象アプリの設定を変更できません", Toast.LENGTH_LONG).show(); adapter.notifyDataSetChanged(); }
    }
    private void approve(String pkg) { Set<String> apps = selected(); apps.add(pkg); prefs.edit().putStringSet("apps", apps).apply(); publish(); adapter.notifyDataSetChanged(); Toast.makeText(this, "有効にしました。対象アプリを開いて確認してください。", Toast.LENGTH_SHORT).show(); }
    private void publish() { try { StyloppApp.publish(this); } catch (RuntimeException error) { Toast.makeText(this, "Vectorへ設定を送信できません", Toast.LENGTH_SHORT).show(); } }
    private void filter(String text) { String query = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT); visible.clear(); for (ApplicationInfo app : apps) if (app.packageName.contains(query) || java.text.Normalizer.normalize(app.loadLabel(getPackageManager()).toString(), java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).contains(query)) visible.add(app); adapter.notifyDataSetChanged(); }
    private void refreshStatus() { String framework; try { XposedService service = StyloppApp.service; framework = service == null ? "VectorでStylo++を有効にしてください" : service.getFrameworkName() + " · API " + service.getApiVersion(); } catch (RuntimeException error) { framework = "フレームワーク未接続"; } status.setText(framework + "\n" + RootPenController.get(this).status()); }
    @Override protected void onResume() { super.onResume(); ui.post(refresh); }
    @Override protected void onPause() { ui.removeCallbacks(refresh); super.onPause(); }
}
