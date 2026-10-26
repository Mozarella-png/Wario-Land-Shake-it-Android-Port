package com.wario.shakeit;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * On-screen remote for Wario Land: Shake It!. It draws the controls over the game, sends their
 * state to the runtime (VirtualRemote::SetTouchState) and lets the player move, resize and fade
 * each one. Positions are fractions of the screen, so they keep their place on any screen,
 * ultrawide included. Touches that miss every control go through to the game.
 */
public class TouchOverlay extends View {

    // VirtualRemote::TouchButton bits (runtime/include/virtual_remote.h).
    static final int LEFT = 0x001, RIGHT = 0x002, UP = 0x004, DOWN = 0x008;
    static final int ONE = 0x010, TWO = 0x020, A = 0x040, PLUS = 0x080, MINUS = 0x100;
    // Not a button bit: passed to the runtime as the shake argument.
    private static final int SHAKE_FLAG = 1 << 20;

    private static final int JOY = 0, ONE_C = 1, TWO_C = 2, A_C = 3, PLUS_C = 4, MINUS_C = 5, SHAKE_C = 6;
    private static final int COUNT = 7;
    private static final String[] IDS = {"joy", "one", "two", "a", "plus", "minus", "shake"};
    private static final String[] LABELS = {"Stick", "1", "2", "A", "+", "-", "Shake"};
    // Defaults as fractions of the screen (center x, center y), for a landscape phone.
    private static final float[] DEF_X = {0.11f, 0.82f, 0.93f, 0.70f, 0.94f, 0.84f, 0.50f};
    private static final float[] DEF_Y = {0.68f, 0.86f, 0.66f, 0.84f, 0.10f, 0.10f, 0.90f};
    // Image per control (every control has artwork now; 0 would mean drawn in code).
    private static final int[] RES = {
            R.drawable.joystick, R.drawable.btn_one, R.drawable.btn_two, R.drawable.btn_a,
            R.drawable.btn_plus, R.drawable.btn_minus, R.drawable.btn_shake};

    private final SharedPreferences prefs;
    private final float density;
    private final float[] cx = new float[COUNT];
    private final float[] cy = new float[COUNT];
    private final float[] scale = new float[COUNT];
    private final float[] alpha = new float[COUNT];
    private final Bitmap[] bitmaps = new Bitmap[COUNT];

    // Pointer id -> control it is pressing, and -> what that press contributes to the remote.
    private final SparseIntArray pointerControl = new SparseIntArray();
    private final SparseIntArray pointerInput = new SparseIntArray();

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean editing;
    private int selected = JOY;
    private int dragControl = -1;
    private int dragPointer = -1;
    private float lastX;
    private float lastY;
    private int lastButtons;
    private boolean lastShake;

    private View panel;
    private final TextView[] chips = new TextView[COUNT];
    private SeekBar sizeBar;
    private SeekBar opacityBar;
    private boolean syncingPanel;

    public TouchOverlay(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        prefs = context.getSharedPreferences("touch_layout", Context.MODE_PRIVATE);
        for (int c = 0; c < COUNT; c++) {
            if (RES[c] != 0) {
                bitmaps[c] = BitmapFactory.decodeResource(context.getResources(), RES[c]);
            }
        }
        loadAll();

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(2));
        ringPaint.setColor(Color.WHITE);
        fillPaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        selectPaint.setStyle(Paint.Style.STROKE);
        selectPaint.setStrokeWidth(dp(2));
        selectPaint.setColor(Color.YELLOW);
    }

    // ---- Layout persistence -------------------------------------------------------------

    private void loadAll() {
        for (int c = 0; c < COUNT; c++) {
            cx[c] = prefs.getFloat(IDS[c] + ".x", DEF_X[c]);
            cy[c] = prefs.getFloat(IDS[c] + ".y", DEF_Y[c]);
            scale[c] = prefs.getFloat(IDS[c] + ".s", 1f);
            alpha[c] = prefs.getFloat(IDS[c] + ".a", 0.8f);
        }
    }

    private void saveAll() {
        SharedPreferences.Editor e = prefs.edit();
        for (int c = 0; c < COUNT; c++) {
            putControl(e, c);
        }
        e.apply();
    }

    private void saveOne(int c) {
        SharedPreferences.Editor e = prefs.edit();
        putControl(e, c);
        e.apply();
    }

    private void putControl(SharedPreferences.Editor e, int c) {
        e.putFloat(IDS[c] + ".x", cx[c])
                .putFloat(IDS[c] + ".y", cy[c])
                .putFloat(IDS[c] + ".s", scale[c])
                .putFloat(IDS[c] + ".a", alpha[c]);
    }

    private void resetAll() {
        prefs.edit().clear().apply();
        loadAll();
        syncPanel();
        invalidate();
    }

    // ---- Geometry ----------------------------------------------------------------------

    private RectF rectOf(int c) {
        float minSide = Math.min(getWidth(), getHeight());
        float base = c == JOY ? 0.34f * minSide : c == SHAKE_C ? 0.24f * minSide : 0.14f * minSide;
        float w = base * scale[c];
        // The shake artwork is 270x94 px.
        float h = c == SHAKE_C ? w * 94f / 270f : w;
        float x = cx[c] * getWidth();
        float y = cy[c] * getHeight();
        return new RectF(x - w / 2f, y - h / 2f, x + w / 2f, y + h / 2f);
    }

    private RectF gearRect() {
        float m = dp(8);
        return new RectF(m, m, m + dp(44), m + dp(44));
    }

    private int hit(float x, float y) {
        for (int c = COUNT - 1; c >= 0; c--) {
            RectF r = rectOf(c);
            if (c == SHAKE_C) {
                if (r.contains(x, y)) {
                    return c;
                }
                continue;
            }
            float rad = r.width() / 2f * 1.1f;
            float dx = x - r.centerX();
            float dy = y - r.centerY();
            if (dx * dx + dy * dy <= rad * rad) {
                return c;
            }
        }
        return -1;
    }

    private float clamp(float v) {
        return Math.max(0.02f, Math.min(0.98f, v));
    }

    // ---- Input -------------------------------------------------------------------------

    private int contribution(int c, float x, float y) {
        if (c == SHAKE_C) {
            return SHAKE_FLAG;
        }
        if (c == JOY) {
            return joystickBits(rectOf(JOY), x, y);
        }
        return bitsFor(c);
    }

    private static int bitsFor(int c) {
        switch (c) {
            case ONE_C: return ONE;
            case TWO_C: return TWO;
            case A_C: return A;
            case PLUS_C: return PLUS;
            case MINUS_C: return MINUS;
            default: return 0;
        }
    }

    // The stick reports the directions the finger is pushed toward; diagonals come from two at once.
    private static int joystickBits(RectF r, float x, float y) {
        float half = r.width() / 2f;
        float dead = 0.3f;
        float dx = (x - r.centerX()) / half;
        float dy = (y - r.centerY()) / half;
        int bits = 0;
        if (dx < -dead) bits |= LEFT;
        if (dx > dead) bits |= RIGHT;
        if (dy < -dead) bits |= UP;
        if (dy > dead) bits |= DOWN;
        return bits;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                return onDown(e.getPointerId(i), e.getX(i), e.getY(i));
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    onMove(e.getPointerId(i), e.getX(i), e.getY(i));
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                onUp(e.getPointerId(e.getActionIndex()));
                return true;
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                return true;
            default:
                return false;
        }
    }

    private boolean onDown(int id, float x, float y) {
        if (editing) {
            int c = hit(x, y);
            if (c >= 0) {
                selected = c;
                dragControl = c;
                dragPointer = id;
                lastX = x;
                lastY = y;
                syncPanel();
            }
            invalidate();
            return true;
        }
        if (gearRect().contains(x, y)) {
            setEditing(true);
            return true;
        }
        int c = hit(x, y);
        if (c < 0) {
            // Not ours: the touch reaches the game underneath.
            return false;
        }
        pointerControl.put(id, c);
        pointerInput.put(id, contribution(c, x, y));
        sendInput();
        invalidate();
        return true;
    }

    private void onMove(int id, float x, float y) {
        if (editing) {
            if (dragControl >= 0 && id == dragPointer) {
                cx[dragControl] = clamp(cx[dragControl] + (x - lastX) / getWidth());
                cy[dragControl] = clamp(cy[dragControl] + (y - lastY) / getHeight());
                lastX = x;
                lastY = y;
                invalidate();
            }
            return;
        }
        int c = pointerControl.get(id, -1);
        if (c < 0) {
            return;
        }
        pointerInput.put(id, contribution(c, x, y));
        sendInput();
        invalidate();
    }

    private void onUp(int id) {
        if (editing) {
            if (id == dragPointer) {
                dragControl = -1;
                dragPointer = -1;
                saveAll();
            }
            return;
        }
        pointerControl.delete(id);
        pointerInput.delete(id);
        sendInput();
        invalidate();
    }

    void releaseAll() {
        pointerControl.clear();
        pointerInput.clear();
        sendInput();
        invalidate();
    }

    // Sends the combined state of every pressed control, only when it changes.
    private void sendInput() {
        int buttons = 0;
        boolean shake = false;
        for (int i = 0; i < pointerInput.size(); i++) {
            int v = pointerInput.valueAt(i);
            buttons |= v & ~SHAKE_FLAG;
            if ((v & SHAKE_FLAG) != 0) {
                shake = true;
            }
        }
        if (buttons == lastButtons && shake == lastShake) {
            return;
        }
        lastButtons = buttons;
        lastShake = shake;
        GameActivity.nativeSetTouchState(buttons, shake, 0f);
    }

    private boolean isPressed(int c) {
        for (int i = 0; i < pointerControl.size(); i++) {
            if (pointerControl.valueAt(i) == c) {
                return true;
            }
        }
        return false;
    }

    // ---- Edit mode ---------------------------------------------------------------------

    void setEditing(boolean on) {
        editing = on;
        if (on) {
            releaseAll();
        } else {
            dragControl = -1;
            dragPointer = -1;
            saveAll();
        }
        if (panel != null) {
            panel.setVisibility(on ? VISIBLE : GONE);
        }
        syncPanel();
        invalidate();
    }

    // The panel is added on top of this view by GameActivity; it holds the size and opacity sliders.
    public View createPanel() {
        Context ctx = getContext();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        box.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6101010);
        bg.setCornerRadius(dp(14));
        box.setBackground(bg);

        box.addView(label("Editar controles", 16));

        LinearLayout chipRow = new LinearLayout(ctx);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < COUNT; i++) {
            final int index = i;
            TextView chip = label(LABELS[i], 14);
            chip.setPadding(dp(8), dp(6), dp(8), dp(6));
            chip.setOnClickListener(v -> {
                selected = index;
                syncPanel();
                invalidate();
            });
            chips[i] = chip;
            chipRow.addView(chip);
        }
        box.addView(chipRow);

        sizeBar = new SeekBar(ctx);
        sizeBar.setMax(150); // 50% .. 200%
        sizeBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && !syncingPanel) {
                    scale[selected] = (progress + 50) / 100f;
                    saveOne(selected);
                    invalidate();
                }
            }
        });
        opacityBar = new SeekBar(ctx);
        opacityBar.setMax(80); // 20% .. 100%
        opacityBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && !syncingPanel) {
                    alpha[selected] = (progress + 20) / 100f;
                    saveOne(selected);
                    invalidate();
                }
            }
        });
        box.addView(label("Tamaño", 13));
        box.addView(sizeBar);
        box.addView(label("Opacidad", 13));
        box.addView(opacityBar);

        LinearLayout actions = new LinearLayout(ctx);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView reset = label("Restaurar", 14);
        reset.setOnClickListener(v -> resetAll());
        TextView done = label("Listo", 14);
        done.setOnClickListener(v -> setEditing(false));
        actions.addView(reset);
        actions.addView(done);
        box.addView(actions);

        TextView changeData = label("Cambiar datos del juego…", 13);
        changeData.setOnClickListener(v -> {
            if (getContext() instanceof GameActivity) {
                ((GameActivity) getContext()).chooseGameData();
            }
        });
        box.addView(changeData);

        panel = box;
        panel.setVisibility(GONE);
        syncPanel();
        return box;
    }

    private void syncPanel() {
        if (panel == null) {
            return;
        }
        for (int i = 0; i < COUNT; i++) {
            chips[i].setTextColor(i == selected ? Color.YELLOW : Color.WHITE);
        }
        syncingPanel = true;
        sizeBar.setProgress(Math.round(scale[selected] * 100f) - 50);
        opacityBar.setProgress(Math.round(alpha[selected] * 100f) - 20);
        syncingPanel = false;
    }

    private TextView label(String text, int sp) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(sp);
        t.setPadding(dp(6), dp(4), dp(6), dp(4));
        return t;
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar bar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar bar) {
        }
    }

    // ---- Drawing -----------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (int c = 0; c < COUNT; c++) {
            drawControl(canvas, c);
        }
        if (!editing) {
            drawGear(canvas);
        }
    }

    private void drawControl(Canvas canvas, int c) {
        RectF r = rectOf(c);
        boolean pressed = isPressed(c);
        int a = Math.round(alpha[c] * 255f);
        if (bitmaps[c] != null) {
            bitmapPaint.setAlpha(pressed ? 255 : a);
            canvas.drawBitmap(bitmaps[c], null, r, bitmapPaint);
        } else {
            fillPaint.setColor(Color.WHITE);
            fillPaint.setAlpha(pressed ? 110 : 40);
            canvas.drawOval(r, fillPaint);
            ringPaint.setAlpha(a);
            canvas.drawOval(r, ringPaint);
            textPaint.setAlpha(a);
            textPaint.setTextSize(r.height() * 0.42f);
            float baseline = r.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f;
            canvas.drawText(LABELS[c], r.centerX(), baseline, textPaint);
        }
        if (editing && c == selected) {
            canvas.drawRoundRect(r.left - dp(6), r.top - dp(6), r.right + dp(6), r.bottom + dp(6),
                    dp(8), dp(8), selectPaint);
        }
    }

    private void drawGear(Canvas canvas) {
        RectF g = gearRect();
        fillPaint.setColor(Color.BLACK);
        fillPaint.setAlpha(90);
        canvas.drawRoundRect(g, dp(10), dp(10), fillPaint);
        textPaint.setAlpha(220);
        textPaint.setTextSize(dp(22));
        float baseline = g.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f;
        canvas.drawText("\u2699", g.centerX(), baseline, textPaint);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
