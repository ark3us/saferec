package net.ark3us.saferec.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.text.Html;
import javax.annotation.Nullable;

import net.ark3us.saferec.R;

public class TutorialOverlayView extends FrameLayout {

    private Paint backgroundPaint;
    private Paint holePaint;
    private Paint arrowPaint;
    private View targetView;
    private OnStepClickListener listener;
    private final int[] targetLocation = new int[2];

    public interface OnStepClickListener {
        void onStepClick();
    }

    public TutorialOverlayView(Context context) {
        super(context);
        init();
    }

    public TutorialOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        setLayerType(LAYER_TYPE_HARDWARE, null);

        backgroundPaint = new Paint();
        backgroundPaint.setColor(Color.parseColor("#CC000000"));

        holePaint = new Paint();
        holePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        holePaint.setAntiAlias(true);

        arrowPaint = new Paint();
        arrowPaint.setColor(Color.WHITE);
        arrowPaint.setStyle(Paint.Style.STROKE);
        arrowPaint.setStrokeWidth(8f);
        arrowPaint.setAntiAlias(true);
        arrowPaint.setStrokeCap(Paint.Cap.ROUND);

        setOnClickListener(v -> {
            if (listener != null)
                listener.onStepClick();
        });
    }

    public void setTarget(View view, CharSequence message, OnStepClickListener listener) {
        setTarget(view, null, message, listener);
    }

    public void setTarget(View view, @Nullable CharSequence title, CharSequence message,
            OnStepClickListener listener) {
        setTarget(view, title, message, 0, listener);
    }

    public void setTarget(View view, @Nullable CharSequence title, CharSequence message,
            int imageResId, OnStepClickListener listener) {
        this.targetView = view;
        this.listener = listener;

        removeAllViews();
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(64, 64, 64, 64);

        if (title != null) {
            TextView titleView = new TextView(getContext());
            titleView.setText(title);
            titleView.setTextColor(Color.WHITE);
            titleView.setTextSize(24f);
            titleView.setGravity(Gravity.CENTER);
            titleView.setAlpha(0.9f);
            titleView.setPadding(0, 0, 0, 32);
            layout.addView(titleView);
        }

        if (imageResId != 0) {
            ImageView imageView = new ImageView(getContext());
            imageView.setImageResource(imageResId);
            imageView.setAdjustViewBounds(true);
            int maxWidth = (int) (getResources().getDisplayMetrics().widthPixels * 0.7f);
            imageView.setMaxWidth(maxWidth);
            imageView.setPadding(0, 0, 0, 32);
            layout.addView(imageView);
        }

        TextView textView = new TextView(getContext());
        if (message instanceof String) {
            textView.setText(Html.fromHtml((String) message, Html.FROM_HTML_MODE_COMPACT));
        } else {
            textView.setText(message);
        }
        textView.setTextColor(Color.WHITE);
        textView.setTextSize(18f);
        textView.setGravity(Gravity.CENTER);
        layout.addView(textView);

        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);

        if (targetView != null) {
            targetView.getLocationInWindow(targetLocation);
            int y = targetLocation[1];
            int height = getResources().getDisplayMetrics().heightPixels;

            if (y > height / 2) {
                params.gravity = Gravity.TOP;
                params.topMargin = height / 5;
            } else {
                params.gravity = Gravity.BOTTOM;
                params.bottomMargin = height / 5;
            }
        } else {
            params.gravity = Gravity.CENTER;
        }

        layout.setLayoutParams(params);
        addView(layout);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(0, 0, getWidth(), getHeight(), backgroundPaint);

        if (targetView != null) {
            targetView.getLocationInWindow(targetLocation);
            float centerX = targetLocation[0] + targetView.getWidth() / 2f;
            float centerY = targetLocation[1] + targetView.getHeight() / 2f;
            float radius = Math.max(targetView.getWidth(), targetView.getHeight()) / 1.5f;

            canvas.drawCircle(centerX, centerY, radius, holePaint);

            // Draw arrow
            drawArrow(canvas, centerX, centerY, radius);
        }
    }

    private void drawArrow(Canvas canvas, float centerX, float centerY, float radius) {
        float arrowLen = 100f;
        float margin = 20f;
        float startY, endY;

        Path path = new Path();
        if (centerY > getHeight() / 2f) {
            // Arrow pointing down
            startY = centerY - radius - arrowLen - margin;
            endY = centerY - radius - margin;
            path.moveTo(centerX, startY);
            path.lineTo(centerX, endY);
            path.lineTo(centerX - 20, endY - 20);
            path.moveTo(centerX, endY);
            path.lineTo(centerX + 20, endY - 20);
        } else {
            // Arrow pointing up
            startY = centerY + radius + arrowLen + margin;
            endY = centerY + radius + margin;
            path.moveTo(centerX, startY);
            path.lineTo(centerX, endY);
            path.lineTo(centerX - 20, endY + 20);
            path.moveTo(centerX, endY);
            path.lineTo(centerX + 20, endY + 20);
        }
        canvas.drawPath(path, arrowPaint);
    }
}
