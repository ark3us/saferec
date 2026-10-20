package net.ark3us.saferec.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.widget.TextViewCompat;

import com.google.android.material.materialswitch.MaterialSwitch;

import net.ark3us.saferec.R;

/**
 * Builds the settings screen's rows: group headers, plain rows and toggle rows.
 * An icon resource of 0 means no icon.
 */
public final class SettingsRows {

    private SettingsRows() {}

    /** A group header without an icon. */
    public static View header(ViewGroup parent, String title) {
        return header(parent, 0, title);
    }

    /** A group header with its icon. */
    public static View header(ViewGroup parent, @DrawableRes int iconRes, String title) {
        TextView header = (TextView) LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_setting_header, parent, false);
        header.setText(title);
        if (iconRes != 0) {
            TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(header, iconRes, 0, 0, 0);
            TextViewCompat.setCompoundDrawableTintList(header, header.getTextColors());
        }
        ViewCompat.setAccessibilityHeading(header, true);
        parent.addView(header);
        return header;
    }

    /**
     * A row that does something when tapped, or only shows a state when listener is null.
     *
     * @return the row; its click listener may be replaced
     */
    public static View row(ViewGroup parent, @DrawableRes int iconRes, String title,
                           @Nullable String summary, @Nullable View.OnClickListener listener) {
        View row = inflate(parent, iconRes, title, summary);
        if (listener != null) {
            row.setOnClickListener(listener);
        } else {
            // Still focusable, so a screen reader reads the title and the summary together
            row.setClickable(false);
            row.setBackground(null);
        }
        parent.addView(row);
        return row;
    }

    /**
     * A row with a switch. A tap on the row flips the switch, which calls listener; the
     * listener is attached after the initial state is set, so building the row calls nothing.
     *
     * @return the row; replacing its click listener leaves the switch as the only way to flip it
     */
    public static View toggle(ViewGroup parent, @DrawableRes int iconRes, String title,
                              @Nullable String summary, boolean checked,
                              CompoundButton.OnCheckedChangeListener listener) {
        View row = inflate(parent, iconRes, title, summary);
        MaterialSwitch toggle = row.findViewById(R.id.setting_switch);
        toggle.setVisibility(View.VISIBLE);
        toggle.setContentDescription(title);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener(listener);
        row.setOnClickListener(v -> toggle.toggle());
        parent.addView(row);
        return row;
    }

    /** Tints a row's summary as a step the user still has to take. */
    public static void markAttention(View row) {
        TextView summary = row.findViewById(R.id.setting_summary);
        summary.setTextColor(row.getContext().getColor(R.color.attention_orange));
    }

    private static View inflate(ViewGroup parent, @DrawableRes int iconRes, String title, @Nullable String summary) {
        View row = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_setting_row, parent, false);
        ((TextView) row.findViewById(R.id.setting_title)).setText(title);
        TextView summaryView = row.findViewById(R.id.setting_summary);
        if (summary != null && !summary.isEmpty()) {
            summaryView.setText(summary);
            summaryView.setVisibility(View.VISIBLE);
        }
        setIcon(row.findViewById(R.id.setting_icon), iconRes);
        return row;
    }

    private static void setIcon(ImageView icon, @DrawableRes int iconRes) {
        if (iconRes != 0) {
            icon.setImageResource(iconRes);
            icon.setVisibility(View.VISIBLE);
        }
    }
}
