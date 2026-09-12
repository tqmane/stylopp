package jp.stylopp;

import android.app.Activity;
import android.view.*;
import android.widget.Button;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.util.ArrayList;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class OverlayTest {
    @Test public void penInkPersistsAndCancelledInkDoesNotReplaceIt() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ArrayList<String> saved = new ArrayList<>();
        PenOverlay overlay = new PenOverlay(activity, saved::add, () -> {});
        activity.setContentView(overlay); overlay.restore("[]"); overlay.setEnabledFeatures(true, true);
        overlay.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY)); overlay.layout(0, 0, 800, 600);
        findButton(overlay, "画面に書く").performClick();
        event(overlay, MotionEvent.ACTION_DOWN, 100, 400);
        event(overlay, MotionEvent.ACTION_MOVE, 200, 420);
        event(overlay, MotionEvent.ACTION_UP, 300, 440);
        assertFalse(saved.isEmpty());
        String original = saved.get(saved.size() - 1);
        assertEquals(1, new org.json.JSONArray(original).length());
        event(overlay, MotionEvent.ACTION_DOWN, 120, 430);
        event(overlay, MotionEvent.ACTION_CANCEL, 130, 450);
        assertEquals(original, overlay.content());
        PenOverlay restored = new PenOverlay(activity, saved::add, () -> {}); restored.restore(original);
        assertEquals(original, restored.content());
    }
    private void event(View view, int action, float x, float y) {
        MotionEvent.PointerProperties p = new MotionEvent.PointerProperties(); p.id = 0; p.toolType = MotionEvent.TOOL_TYPE_STYLUS;
        MotionEvent.PointerCoords c = new MotionEvent.PointerCoords(); c.x = x; c.y = y; c.pressure = .7f;
        MotionEvent event = MotionEvent.obtain(100, 120 + action * 10, action, 1, new MotionEvent.PointerProperties[]{p}, new MotionEvent.PointerCoords[]{c}, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_STYLUS, 0);
        view.dispatchTouchEvent(event); event.recycle();
    }
    private Button findButton(View view, String text) {
        if (view instanceof Button && text.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { Button found = findButton(((ViewGroup) view).getChildAt(i), text); if (found != null) return found; }
        return null;
    }
}
