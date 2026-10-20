package net.ark3us.saferec.ui;

import android.os.Bundle;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.button.MaterialButton;

import net.ark3us.saferec.BuildConfig;
import net.ark3us.saferec.MainActivity;
import net.ark3us.saferec.PermissionsManager;
import net.ark3us.saferec.R;
import net.ark3us.saferec.misc.Settings;

import java.util.List;

/**
 * The first run: up to three cards above the viewfinder's controls. It never blocks the record
 * button (the card is anchored above the controls and never waits for anything), and the same
 * cards are the Guide in Settings.
 */
public class FirstRun {
    private final MainActivity activity;
    private final View card;
    private final PermissionsManager permissions;
    private final View controls;
    private final TextView title;
    private final TextView text;
    private final LinearLayout dots;
    private final MaterialButton primary;
    private List<FirstRunSteps.Step> steps;
    private int index;

    public FirstRun(@NonNull MainActivity activity, @NonNull View card, @NonNull PermissionsManager permissions) {
        this.activity = activity;
        this.card = card;
        this.permissions = permissions;
        this.controls = activity.findViewById(R.id.bottom_controls);
        title = card.findViewById(R.id.first_run_title);
        text = card.findViewById(R.id.first_run_text);
        dots = card.findViewById(R.id.first_run_dots);
        primary = card.findViewById(R.id.first_run_primary);
        card.findViewById(R.id.first_run_skip).setOnClickListener(v -> finish());
        primary.setOnClickListener(v -> onPrimary());
        // Sits right above the controls, whatever their height (bottom inset included)
        controls.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> anchor());
    }

    private void anchor() {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) card.getLayoutParams();
        int bottom = controls.getHeight();
        if (lp.bottomMargin != bottom) {
            lp.bottomMargin = bottom;
            card.setLayoutParams(lp);
        }
    }

    /** From the first step. */
    public void show() {
        steps = FirstRunSteps.steps(BuildConfig.POWER_GESTURE,
                PermissionsManager.hasRecordingPermissions(activity));
        index = 0;
        anchor();
        render();
        card.setVisibility(View.VISIBLE);
    }

    public void hide() {
        card.setVisibility(View.GONE);
    }

    public boolean isShowing() {
        return card.getVisibility() == View.VISIBLE;
    }

    private boolean isLast() {
        return index >= steps.size() - 1;
    }

    private void render() {
        FirstRunSteps.Step step = steps.get(index);
        int actionLabel;
        switch (step) {
            case PERMISSIONS:
                title.setText(R.string.first_run_permissions_title);
                text.setText(R.string.first_run_permissions_text);
                actionLabel = R.string.first_run_allow;
                break;
            case THREE_PRESSES:
                title.setText(R.string.first_run_three_presses_title);
                actionLabel = renderThreePresses();
                break;
            default:
                title.setText(R.string.first_run_about_title);
                text.setText(R.string.first_run_about_text);
                actionLabel = isLast() ? R.string.first_run_done : R.string.first_run_next;
                break;
        }
        primary.setText(actionLabel);
        renderDots();
    }

    private void renderDots() {
        dots.removeAllViews();
        float d = activity.getResources().getDisplayMetrics().density;
        int size = Math.round(8 * d);
        for (int i = 0; i < steps.size(); i++) {
            View dot = new View(activity);
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(activity.getColor(i == index ? R.color.on_glass : R.color.on_glass_dim));
            dot.setBackground(shape);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(Math.round(6 * d));
            dots.addView(dot, lp);
        }
        dots.setContentDescription(activity.getString(R.string.first_run_step, index + 1, steps.size()));
    }

    private void onPrimary() {
        switch (steps.get(index)) {
            case PERMISSIONS:
                // Whatever the answer, the card moves on: it never insists
                permissions.requestRecordingPermissions(this::next);
                break;
            case THREE_PRESSES:
                // The card stays until the three presses work (or Skip): it re-reads the status on resume
                if (BuildConfig.POWER_GESTURE) {
                    onThreePressesPrimary();
                }
                render();
                break;
            default:
                next();
                break;
        }
    }

    /*
     * The guard is compileOnly without the build switch. Its code sits in the bodies below,
     * inside the constant check, so javac leaves it out of that build. No switch over a guard
     * enum here: its map would join FirstRun$1, which render() loads (GuardSetup has them).
     */

    /** The three presses card for what the guard still needs. Returns the button's label. */
    private int renderThreePresses() {
        if (BuildConfig.POWER_GESTURE) {
            int state = GuardSetup.state(activity);
            switch (state) {
                case GuardSetup.STATE_NEEDS_OVERLAY:
                case GuardSetup.STATE_NEEDS_BATTERY:
                    text.setText(GuardSetup.stepText(state));
                    return R.string.first_run_continue;
                case GuardSetup.STATE_STOPPED:
                    text.setText(R.string.three_presses_starting);
                    return R.string.first_run_done;
                case GuardSetup.STATE_ACTIVE:
                    text.setText(R.string.three_presses_on);
                    return R.string.first_run_done;
                default:
                    break;
            }
        }
        text.setText(R.string.first_run_three_presses_text);
        return R.string.first_run_turn_on;
    }

    private void onThreePressesPrimary() {
        if (BuildConfig.POWER_GESTURE) {
            switch (GuardSetup.state(activity)) {
                case GuardSetup.STATE_STOPPED:
                case GuardSetup.STATE_ACTIVE:
                    finish();
                    break;
                case GuardSetup.STATE_OFF:
                    net.ark3us.saferec.guard.Guard.setEnabled(activity, true);
                    GuardSetup.openNextStep(activity);
                    break;
                default:
                    GuardSetup.openNextStep(activity);
                    break;
            }
        }
    }

    /** Back from a settings page: the three presses step shows what is still missing. */
    public void onResume() {
        if (isShowing() && steps.get(index) == FirstRunSteps.Step.THREE_PRESSES) {
            render();
        }
    }

    private static final String STATE_INDEX = "first_run_index";
    private static final String STATE_SHOWING = "first_run_showing";

    public void save(@NonNull Bundle out) {
        out.putBoolean(STATE_SHOWING, isShowing());
        out.putInt(STATE_INDEX, index);
    }

    /** After a recreation: the card comes back on the step it was. Returns whether it did. */
    public boolean restore(@NonNull Bundle in) {
        if (!in.getBoolean(STATE_SHOWING, false)) return false;
        show();
        index = Math.max(0, Math.min(in.getInt(STATE_INDEX, 0), steps.size() - 1));
        render();
        return true;
    }

    private void next() {
        if (!isShowing()) return;
        if (isLast()) {
            finish();
        } else {
            index++;
            render();
        }
    }

    private void finish() {
        Settings.setFirstRunDone(activity, true);
        hide();
    }
}
