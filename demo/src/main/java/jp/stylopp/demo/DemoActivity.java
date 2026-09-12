package jp.stylopp.demo;

import android.app.Activity;
import android.os.*;
import android.view.*;
import android.webkit.WebView;
import android.widget.*;

/** Offline QA browser. No network, existing browser tabs or user notebook files are involved. */
public final class DemoActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setOnApplyWindowInsetsListener((view, insets) -> { android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()); view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets; });
        Button test = new Button(this); test.setText("ペンの試験入力（新しい検証用の線）"); test.setOnClickListener(v -> simulate()); page.addView(test);
        Button recreate = new Button(this); recreate.setText("画面を再作成して保存を確認"); recreate.setOnClickListener(v -> recreate()); page.addView(recreate);
        WebView web = new WebView(this); web.setBackgroundColor(0xfff6f7fa);
        web.loadDataWithBaseURL(null, "<html><meta name='viewport' content='width=device-width'><body style='font-family:sans-serif;padding:48px;background:#f6f7fa;color:#243047'><small>STYLO++ / OFFLINE TEST</small><h1>ブラウザでも、ペンを。</h1><p>このページ自体には手書き機能がありません。</p><p>Stylo++の「画面に書く」を押して、ペンで書いてみてください。</p><button style='padding:14px'>普通のWebボタン</button><p><input placeholder='普通の入力欄' style='padding:14px'></p></body></html>", "text/html", "UTF-8", null);
        page.addView(web, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(page); page.requestApplyInsets();
        if (getIntent().getBooleanExtra("simulate", false)) page.postDelayed(this::simulate, 1200);
    }
    private void simulate() {
        final long start = SystemClock.uptimeMillis(); final Handler ui = new Handler(Looper.getMainLooper());
        for (int i = 0; i < 35; i++) { final int step = i; ui.postDelayed(() -> {
            MotionEvent.PointerProperties property = new MotionEvent.PointerProperties(); property.id = 0; property.toolType = MotionEvent.TOOL_TYPE_STYLUS;
            MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords(); coords.x = getWindow().getDecorView().getWidth() * (0.4f + step * 0.006f); coords.y = getWindow().getDecorView().getHeight() * (0.6f + (float)Math.sin(step / 4f) * 0.04f); coords.pressure = 0.6f; coords.size = 0.1f;
            MotionEvent event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), step == 0 ? MotionEvent.ACTION_DOWN : step == 34 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE, 1, new MotionEvent.PointerProperties[]{property}, new MotionEvent.PointerCoords[]{coords}, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_STYLUS, 0);
            dispatchTouchEvent(event); event.recycle();
        }, 500 + i * 25L); }
    }
}
