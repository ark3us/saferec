package net.ark3us.saferec.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import net.ark3us.saferec.R;

/**
 * The record button. Ready: one tap starts. Recording: the button must be held for about a
 * second while a ring fills, so a recording is never stopped by accident.
 */
public class RecordButton extends View {
    public interface Listener {
        void onStartRequested();

        void onStopConfirmed();

        /** A tap while recording: tell the user to hold. */
        void onStopHintNeeded();
    }

    private static final long RELEASE_ANIM_MS = 150;
    private static final float BUSY_ALPHA = 0.4f;

    private final HoldToStop hold = new HoldToStop();
    private final RecordGesture gesture = new RecordGesture();
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final float visualPx;
    private final float touchPx;
    private final float strokePx;
    private final float cornerPx;

    @Nullable private Listener listener;
    private boolean recording;
    private boolean busy;
    private boolean holding;
    private int stopActionId = View.NO_ID;
    private final Runnable stopCheck = this::checkStop;
    private float releasedFrom;
    private long releasedAt;

    public RecordButton(Context context) {
        this(context, null);
    }

    public RecordButton(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RecordButton(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float density = getResources().getDisplayMetrics().density;
        visualPx = getResources().getDimension(R.dimen.record_visual);
        touchPx = getResources().getDimension(R.dimen.record_touch);
        strokePx = 4 * density;
        cornerPx = 6 * density;
        int glass = ContextCompat.getColor(context, R.color.on_glass);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(strokePx);
        ringPaint.setColor(glass);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(strokePx);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setColor(glass);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(ContextCompat.getColor(context, R.color.rec_red));
        setClickable(true);
        setFocusable(true);
        updateDescription();
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    public void setRecording(boolean recording) {
        if (this.recording == recording) return;
        this.recording = recording;
        cancelHold(false);
        if (recording) {
            stopActionId = ViewCompat.addAccessibilityAction(this,
                    getContext().getString(R.string.record_stop_action), (v, a) -> {
                        if (listener != null) listener.onStopConfirmed();
                        return true;
                    });
        } else if (stopActionId != View.NO_ID) {
            ViewCompat.removeAccessibilityAction(this, stopActionId);
            stopActionId = View.NO_ID;
        }
        updateDescription();
        invalidate();
    }

    /** True while a command is on its way: the button is dimmed and ignores touches. */
    public void setBusy(boolean busy) {
        if (this.busy == busy) return;
        this.busy = busy;
        if (busy) cancelHold(false);
        invalidate();
    }

    private void updateDescription() {
        setContentDescription(getContext().getString(
                recording ? R.string.record_recording_description : R.string.record_start));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = Math.round(touchPx);
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (busy) {
            // A command is on its way: this touch can no longer act, even once it is done
            gesture.cancel();
            return false;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                gesture.down(recording);
                if (recording) {
                    hold.down(now());
                    holding = true;
                    postDelayed(stopCheck, HoldToStop.HOLD_MS);
                    postInvalidateOnAnimation();
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (holding && !checkStop() && !inside(e)) cancelHold(true);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                cancelHold(true);
                return true;
            case MotionEvent.ACTION_UP: {
                // A hold that reached the second by now stops here
                if (holding) checkStop();
                boolean stillHolding = holding;
                // Still held and not confirmed: an early release, the ring animates back
                if (stillHolding) cancelHold(true);
                // Judged by the state the touch began in: the end of a stop never starts again
                if (gesture.release(recording, inside(e), stillHolding) != RecordGesture.Release.NOTHING) {
                    performClick();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                cancelHold(true);
                gesture.cancel();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    /** A tap or a TalkBack double tap. While recording it never stops: it only hints. */
    @Override
    public boolean performClick() {
        super.performClick();
        if (busy || listener == null) return true;
        if (recording) {
            listener.onStopHintNeeded();
        } else {
            listener.onStartRequested();
        }
        return true;
    }

    /** Decides the stop on the clock, not on a frame. True when this call confirmed it. */
    private boolean checkStop() {
        if (!holding || !hold.consumeStop(now())) return false;
        cancelHold(false);
        if (listener != null) listener.onStopConfirmed();
        invalidate();
        return true;
    }

    private boolean inside(MotionEvent e) {
        return e.getX() >= 0 && e.getX() <= getWidth() && e.getY() >= 0 && e.getY() <= getHeight();
    }

    private void cancelHold(boolean animate) {
        removeCallbacks(stopCheck);
        if (holding) {
            releasedFrom = hold.progress(now());
            releasedAt = now();
            holding = false;
            hold.cancel();
            if (animate) postInvalidateOnAnimation();
        } else {
            releasedFrom = 0f;
        }
        if (!animate) releasedFrom = 0f;
    }

    private static long now() {
        return SystemClock.uptimeMillis();
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelHold(false);
        super.onDetachedFromWindow();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) cancelHold(false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        int alpha = Math.round(255 * (busy ? BUSY_ALPHA : 1f));
        ringPaint.setAlpha(alpha);
        arcPaint.setAlpha(alpha);
        fillPaint.setAlpha(alpha);

        float ringR = visualPx / 2f - strokePx / 2f;
        canvas.drawCircle(cx, cy, ringR, ringPaint);

        if (recording) {
            float half = visualPx * 0.4f / 2f;
            oval.set(cx - half, cy - half, cx + half, cy + half);
            canvas.drawRoundRect(oval, cornerPx, cornerPx, fillPaint);
        } else {
            canvas.drawCircle(cx, cy, visualPx * 0.7f / 2f, fillPaint);
        }

        float progress = 0f;
        boolean again = false;
        if (holding) {
            long t = now();
            progress = hold.progress(t);
            again = true;
        } else if (releasedFrom > 0f) {
            float left = 1f - (now() - releasedAt) / (float) RELEASE_ANIM_MS;
            if (left > 0f) {
                progress = releasedFrom * left;
                again = true;
            } else {
                releasedFrom = 0f;
            }
        }
        if (progress > 0f) {
            oval.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR);
            canvas.drawArc(oval, -90f, 360f * progress, false, arcPaint);
        }
        if (again) postInvalidateOnAnimation();
    }
}
