package net.ark3us.saferec.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

/**
 * A scroll view that wraps its content up to 45% of the screen height, then scrolls. The
 * first-run card sits above the controls and grows upward: a long text (large font, translation)
 * must never reach the top bar.
 */
public class CappedScrollView extends NestedScrollView {
    private static final float MAX_SCREEN_FRACTION = 0.45f;

    public CappedScrollView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int cap = (int) (getResources().getDisplayMetrics().heightPixels * MAX_SCREEN_FRACTION);
        // Keep what the parent offers (a weighted height in the card): the cap is only an upper bound
        int mode = View.MeasureSpec.getMode(heightMeasureSpec);
        int size = View.MeasureSpec.getSize(heightMeasureSpec);
        int spec = mode == View.MeasureSpec.UNSPECIFIED
                ? View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST)
                : View.MeasureSpec.makeMeasureSpec(Math.min(size, cap), mode);
        super.onMeasure(widthMeasureSpec, spec);
    }
}
