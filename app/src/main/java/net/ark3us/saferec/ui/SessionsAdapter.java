package net.ark3us.saferec.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.TextViewCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import net.ark3us.saferec.R;
import net.ark3us.saferec.net.SessionSummary;

import java.util.Date;
import java.util.Locale;

/**
 * The recordings list: one card per session. The title, duration and status helpers are static
 * because the session screen shows the same words for the same session.
 */
public final class SessionsAdapter extends ListAdapter<SessionSummary, SessionsAdapter.Holder> {

    public interface Listener {
        void onSessionClick(SessionSummary session);
    }

    private static final DiffUtil.ItemCallback<SessionSummary> DIFF = new DiffUtil.ItemCallback<SessionSummary>() {
        @Override
        public boolean areItemsTheSame(@NonNull SessionSummary a, @NonNull SessionSummary b) {
            return (a.sessionId + "|" + a.dataType).equals(b.sessionId + "|" + b.dataType);
        }

        @Override
        public boolean areContentsTheSame(@NonNull SessionSummary a, @NonNull SessionSummary b) {
            // The summary is rebuilt on every load: compare what the card draws
            return a.status == b.status
                    && a.durationMillis() == b.durationMillis()
                    && a.pieces.size() == b.pieces.size()
                    && java.util.Objects.equals(a.thumbnailLink(), b.thumbnailLink());
        }
    };

    private final ThumbnailLoader thumbnails;
    private final Listener listener;
    private String accessToken = "";

    public SessionsAdapter(ThumbnailLoader thumbnails, Listener listener) {
        super(DIFF);
        this.thumbnails = thumbnails;
        this.listener = listener;
    }

    /** The token the thumbnails are fetched with; set before the list that needs it. */
    public void setAccessToken(String token) {
        this.accessToken = token != null ? token : "";
    }

    // ---- Words shared with the session screen ----

    /** Date and time, in the phone's format. */
    public static String formatTitle(Context context, long startMillis) {
        Date date = new Date(startMillis);
        return DateFormat.getMediumDateFormat(context).format(date) + " "
                + DateFormat.getTimeFormat(context).format(date);
    }

    /** {@code m:ss}, or {@code h:mm:ss} from one hour. */
    public static String formatDuration(long ms) {
        long total = Math.max(0, ms) / 1000;
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long seconds = total % 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }

    /** Icon, label and color of a session's status. */
    public static void bindStatus(TextView chip, SessionSummary.Status status) {
        Context context = chip.getContext();
        @DrawableRes int icon;
        int label;
        int color;
        switch (status) {
            case COMPLETE:
                icon = R.drawable.ms_cloud_done;
                label = R.string.session_status_complete;
                color = R.color.safe_green;
                break;
            case UPLOADING:
                icon = R.drawable.ms_cloud_upload;
                label = R.string.session_status_uploading;
                color = R.color.on_glass;
                break;
            case INCOMPLETE:
                icon = R.drawable.ms_sync_problem;
                label = R.string.session_status_incomplete;
                color = R.color.attention_orange;
                break;
            default:
                icon = R.drawable.ms_help;
                label = R.string.session_status_not_checked;
                color = R.color.on_glass_dim;
                break;
        }
        int tint = ContextCompat.getColor(context, color);
        int size = (int) (16 * context.getResources().getDisplayMetrics().density);
        Drawable drawable = ContextCompat.getDrawable(context, icon).mutate();
        drawable.setBounds(0, 0, size, size);
        chip.setCompoundDrawablesRelative(drawable, null, null, null);
        TextViewCompat.setCompoundDrawableTintList(chip, ColorStateList.valueOf(tint));
        chip.setTextColor(tint);
        chip.setText(label);
    }

    // ---- List ----

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_session_card, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(getItem(position));
    }

    public final class Holder extends RecyclerView.ViewHolder {
        private final ImageView thumbnail;
        private final TextView title;
        private final TextView subtitle;
        private final TextView status;

        Holder(@NonNull View itemView) {
            super(itemView);
            thumbnail = itemView.findViewById(R.id.session_thumbnail);
            title = itemView.findViewById(R.id.session_title);
            subtitle = itemView.findViewById(R.id.session_subtitle);
            status = itemView.findViewById(R.id.session_status);
            thumbnail.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            thumbnail.setClipToOutline(true);
        }

        void bind(SessionSummary session) {
            Context context = itemView.getContext();
            boolean video = "video".equals(session.dataType);
            String titleText = formatTitle(context, session.startMillis);
            String durationText = formatDuration(session.durationMillis());
            String typeText = context.getString(video ? R.string.mode_video : R.string.mode_audio);
            subtitle.setText(durationText + " · " + typeText);
            title.setText(titleText);
            bindStatus(status, session.status);
            itemView.setContentDescription(context.getString(R.string.session_card_description,
                    titleText, durationText, typeText, status.getText()));
            itemView.setOnClickListener(v -> listener.onSessionClick(session));

            showPlaceholderStyle();
            thumbnails.load(thumbnail, video ? session.thumbnailLink() : null, accessToken,
                    video ? R.drawable.ms_videocam : R.drawable.ms_graphic_eq, this::showPictureStyle);
        }

        /** A small dim icon on the glass tile. */
        private void showPlaceholderStyle() {
            int pad = (int) (24 * itemView.getResources().getDisplayMetrics().density);
            thumbnail.setPadding(pad, pad, pad, pad);
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            thumbnail.setImageTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(itemView.getContext(), R.color.on_glass_dim)));
        }

        /** A real picture fills the tile, untinted. */
        private void showPictureStyle() {
            thumbnail.setPadding(0, 0, 0, 0);
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnail.setImageTintList(null);
        }
    }
}
