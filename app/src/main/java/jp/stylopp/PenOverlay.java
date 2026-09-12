package jp.stylopp;

import android.app.Activity;
import android.content.ContentValues;
import android.graphics.*;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.OutputStream;
import java.util.*;
import java.util.function.Consumer;

/** Screen-fixed ink only. It never reads the host's document, URL, pixels or private storage. */
final class PenOverlay extends FrameLayout {
    private static final int MAX_POINTS = 20000;
    private final Activity activity;
    private final Consumer<String> save;
    private final Drawing drawing;
    private final LinearLayout tools;
    private final HorizontalScrollView palette;
    private Button write;
    private boolean writeMode, eraser, loaded, showCursor, showTools;
    private int color = 0xff2766df;
    private float hoverX = -1, hoverY = -1;
    private final ArrayList<Ink> ink = new ArrayList<>();
    private final ArrayDeque<boolean[]> history = new ArrayDeque<>();
    private Ink pending;
    private int pointer = -1;
    private int pointCount;
    private static final class Ink {
        int color; float width; boolean hidden; final ArrayList<float[]> points = new ArrayList<>();
    }
    PenOverlay(Activity activity, Consumer<String> save, Runnable newSheet) {
        super(activity); this.activity = activity; this.save = save;
        setClipChildren(false);
        drawing = new Drawing(); addView(drawing, new LayoutParams(-1, -1));
        tools = new LinearLayout(activity); palette = new HorizontalScrollView(activity); palette.setHorizontalScrollBarEnabled(false); tools.setGravity(Gravity.CENTER_VERTICAL); tools.setPadding(dp(5), dp(4), dp(5), dp(4));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable(); background.setColor(0xee2a2a2a); background.setCornerRadius(dp(24)); palette.setBackground(background); palette.setElevation(dp(8));
        TextView grip = new TextView(activity); grip.setText("⠿"); grip.setTextColor(Color.LTGRAY); grip.setGravity(Gravity.CENTER); grip.setContentDescription("Stylo++のツールを移動"); tools.addView(grip, new LinearLayout.LayoutParams(dp(32), dp(42)));
        grip.setOnTouchListener(new OnTouchListener() { float x, y, startX, startY; @Override public boolean onTouch(View view, MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { x = event.getRawX(); y = event.getRawY(); startX = palette.getTranslationX(); startY = palette.getTranslationY(); }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) { palette.setTranslationX(Math.max(-palette.getLeft(), Math.min(getWidth() - palette.getRight(), startX + event.getRawX() - x))); palette.setTranslationY(Math.max(-palette.getTop(), Math.min(getHeight() - palette.getBottom(), startY + event.getRawY() - y))); }
            return true;
        } });
        write = button("準備中", () -> { writeMode = !writeMode; write.setText(writeMode ? "操作に戻る" : "画面に書く"); }); write.setEnabled(false);
        button("消しゴム", () -> { eraser = !eraser; Toast.makeText(activity, eraser ? "消しゴム" : "ペン", Toast.LENGTH_SHORT).show(); });
        button("戻す", this::undo);
        button("色", () -> { color = color == 0xff2766df ? 0xffd54b62 : color == 0xffd54b62 ? 0xff222222 : 0xff2766df; Toast.makeText(activity, color == 0xff222222 ? "墨色" : color == 0xff2766df ? "ブルー" : "ローズ", Toast.LENGTH_SHORT).show(); });
        button("PNG", this::exportInk);
        button("新規", () -> new android.app.AlertDialog.Builder(activity).setTitle("新しい手書きシート").setMessage("今の筆跡を保存して、新しいシートを開きます。前のシートもStylo++内に保持します。").setPositiveButton("新しく書く", (d, w) -> newSheet.run()).setNegativeButton("キャンセル", null).show());
        LayoutParams layout = new LayoutParams(-2, dp(52), Gravity.TOP | Gravity.END); layout.topMargin = dp(18); layout.rightMargin = dp(12); palette.addView(tools, new FrameLayout.LayoutParams(-2, -1)); addView(palette, layout);
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { super.onSizeChanged(w, h, oldw, oldh); LayoutParams layout = (LayoutParams) palette.getLayoutParams(); layout.width = Math.min(dp(520), Math.max(dp(160), w - dp(24))); palette.setLayoutParams(layout); palette.setTranslationX(0); palette.setTranslationY(0); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private Button button(String text, Runnable action) { Button button = new Button(activity); button.setText(text); button.setTextSize(11); button.setTextColor(Color.WHITE); button.setMinimumWidth(0); button.setMinWidth(0); button.setMinimumHeight(0); button.setMinHeight(0); button.setBackgroundColor(Color.TRANSPARENT); button.setPadding(dp(9), 0, dp(9), 0); button.setOnClickListener(v -> action.run()); tools.addView(button, new LinearLayout.LayoutParams(-2, dp(42))); return button; }
    boolean isErasing() { return writeMode && eraser; }
    void setEnabledFeatures(boolean toolsEnabled, boolean cursor) { showTools = toolsEnabled; showCursor = cursor; if (!showTools) writeMode = false; palette.setVisibility(showTools ? VISIBLE : GONE); if (!cursor) hoverX = -1; drawing.invalidate(); }
    void restore(String json) {
        if (loaded) return;
        try {
            JSONArray array = new JSONArray(json); ArrayList<Ink> restored = new ArrayList<>(); int count = 0;
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i); Ink stroke = new Ink(); stroke.color = object.getInt("color"); stroke.width = (float) object.getDouble("width"); stroke.hidden = object.optBoolean("hidden");
                if (!Float.isFinite(stroke.width) || stroke.width < 0.5f || stroke.width > 30f) throw new IllegalArgumentException();
                JSONArray points = object.getJSONArray("points");
                for (int j = 0; j < points.length(); j++) { JSONArray point = points.getJSONArray(j); float x = (float) point.getDouble(0), y = (float) point.getDouble(1), p = (float) point.getDouble(2);
                    if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(p) || x < -1 || x > 2 || y < -1 || y > 2 || p < 0 || p > 1 || ++count > MAX_POINTS) throw new IllegalArgumentException();
                    stroke.points.add(new float[]{x, y, p});
                } restored.add(stroke);
            }
            ink.clear(); ink.addAll(restored); pointCount = count; loaded = true; write.setEnabled(true); write.setText("画面に書く"); drawing.invalidate();
        } catch (Exception error) { write.setText("復元できません"); write.setEnabled(false); }
    }
    void connectionFailed() { if (!loaded) { write.setText("接続を確認"); write.setEnabled(false); } }
    void hover(MotionEvent event) {
        if (!showCursor || event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS || event.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT) hoverX = -1;
        else { int[] position = new int[2]; getLocationInWindow(position); hoverX = event.getX() - position[0]; hoverY = event.getY() - position[1]; }
        drawing.invalidate();
    }
    private void snapshot() { boolean[] states = new boolean[ink.size()]; for (int i = 0; i < ink.size(); i++) states[i] = ink.get(i).hidden; history.addLast(states); while (history.size() > 30) history.removeFirst(); }
    private void undo() { if (history.isEmpty()) return; boolean[] states = history.removeLast(); for (int i = 0; i < ink.size(); i++) ink.get(i).hidden = i >= states.length || states[i]; persist(); drawing.invalidate(); }
    void finish() { if (pending != null) { ink.add(pending); pending = null; } if (pointer != -1) persist(); pointer = -1; drawing.invalidate(); }
    String content() {
        try { JSONArray array = new JSONArray(); for (Ink stroke : ink) { JSONArray points = new JSONArray(); for (float[] point : stroke.points) points.put(new JSONArray().put(point[0]).put(point[1]).put(point[2]));
            array.put(new JSONObject().put("color", stroke.color).put("width", stroke.width).put("hidden", stroke.hidden).put("points", points)); }
            return array.toString();
        } catch (JSONException error) { throw new IllegalStateException(error); }
    }
    void newSheetReady() { loaded = false; pending = null; pointer = -1; history.clear(); restore("[]"); }
    private void persist() { if (loaded) save.accept(content()); }
    private final class Drawing extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Drawing() { super(activity); setContentDescription("Stylo++の画面固定の手書き"); }
        @Override protected void onDraw(Canvas canvas) { super.onDraw(canvas); if (showTools) { for (Ink stroke : ink) if (!stroke.hidden) drawInk(canvas, stroke, paint); if (pending != null) drawInk(canvas, pending, paint); }
            if (showCursor && hoverX >= 0) { paint.setColor(0xff677a8e); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); canvas.drawCircle(hoverX, hoverY, dp(3), paint); }
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            boolean pen = event.getToolType(event.getActionIndex()) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(event.getActionIndex()) == MotionEvent.TOOL_TYPE_ERASER;
            if (!writeMode || !loaded || (!pen && pointer < 0)) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                if (pointCount >= MAX_POINTS) { Toast.makeText(activity, "この手書きの容量に達しました。PNGで書き出してください。", Toast.LENGTH_LONG).show(); return true; }
                snapshot(); pointer = event.getPointerId(0); requestUnbufferedDispatch(event);
                if (!eraser && event.getToolType(0) != MotionEvent.TOOL_TYPE_ERASER) { pending = new Ink(); pending.color = color; pending.width = 2.3f; }
            }
            int index = event.findPointerIndex(pointer);
            if (action == MotionEvent.ACTION_CANCEL || (event.getFlags() & MotionEvent.FLAG_CANCELED) != 0) { if (pending != null) pointCount -= pending.points.size(); pending = null; pointer = -1; if (!history.isEmpty()) { boolean[] states = history.removeLast(); for (int i = 0; i < states.length; i++) ink.get(i).hidden = states[i]; } invalidate(); return true; }
            if (index >= 0) {
                for (int h = 0; h < event.getHistorySize(); h++) point(event.getHistoricalX(index, h), event.getHistoricalY(index, h), event.getHistoricalPressure(index, h));
                point(event.getX(index), event.getY(index), event.getPressure(index));
            }
            if (action == MotionEvent.ACTION_UP || (action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.getActionIndex()) == pointer)) { if (pending != null) ink.add(pending); pending = null; pointer = -1; persist(); performClick(); }
            invalidate(); return true;
        }
        private void point(float x, float y, float pressure) {
            if (!Float.isFinite(x) || !Float.isFinite(y)) return;
            x = Math.max(0, Math.min(getWidth(), x)); y = Math.max(0, Math.min(getHeight(), y));
            if (!Float.isFinite(pressure)) pressure = 0.5f;
            if (pending != null) { if (pointCount++ < MAX_POINTS) pending.points.add(new float[]{Math.round(x / Math.max(1, getWidth()) * 100000f) / 100000f, Math.round(y / Math.max(1, getHeight()) * 100000f) / 100000f, Math.max(0f, Math.min(1f, pressure))}); }
            else for (Ink stroke : ink) if (!stroke.hidden) for (float[] p : stroke.points) if (Math.hypot(p[0] * getWidth() - x, p[1] * getHeight() - y) < dp(16)) { stroke.hidden = true; break; }
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }
    private void drawInk(Canvas canvas, Ink stroke, Paint paint) {
        if (stroke.points.isEmpty()) return;
        paint.setColor(stroke.color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        float[] first = stroke.points.get(0); float lastX = first[0] * getWidth(), lastY = first[1] * getHeight();
        for (int i = 1; i < stroke.points.size(); i++) { float[] p = stroke.points.get(i); float x = p[0] * getWidth(), y = p[1] * getHeight(); paint.setStrokeWidth(dp(stroke.width) * (0.4f + 0.8f * p[2])); canvas.drawLine(lastX, lastY, x, y, paint); lastX = x; lastY = y; }
        if (stroke.points.size() == 1) { paint.setStyle(Paint.Style.FILL); canvas.drawCircle(lastX, lastY, dp(stroke.width) / 2f, paint); }
    }
    private void exportInk() {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        Bitmap image = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image); Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); for (Ink stroke : ink) if (!stroke.hidden) drawInk(canvas, stroke, paint);
        new Thread(() -> {
            try {
                ContentValues values = new ContentValues(); values.put(MediaStore.Images.Media.DISPLAY_NAME, "stylopp-" + System.currentTimeMillis() + ".png"); values.put(MediaStore.Images.Media.MIME_TYPE, "image/png"); values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Stylopp"); values.put(MediaStore.Images.Media.IS_PENDING, 1);
                android.net.Uri uri = activity.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values); if (uri == null) throw new IllegalStateException();
                try (OutputStream stream = activity.getContentResolver().openOutputStream(uri)) { if (stream == null || !image.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IllegalStateException(); }
                values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0); activity.getContentResolver().update(uri, values, null, null);
                activity.runOnUiThread(() -> Toast.makeText(activity, "Pictures/Stylopp に筆跡を保存しました", Toast.LENGTH_LONG).show());
            } catch (Exception error) { activity.runOnUiThread(() -> Toast.makeText(activity, "PNGを書き出せませんでした。アプリ内の筆跡は保持しています。", Toast.LENGTH_LONG).show()); }
            finally { image.recycle(); }
        }, "stylopp-png").start();
    }
}
