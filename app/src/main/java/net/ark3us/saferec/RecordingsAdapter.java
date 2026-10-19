package net.ark3us.saferec;

import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.api.services.drive.model.File;

import net.ark3us.saferec.net.DriveRecordings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RecordingsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final String TAG = RecordingsAdapter.class.getSimpleName();

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;

    public interface OnItemClickListener {
        void onItemClick(File file);

        void onSelectionChanged(int count);

        void onMergeSession(String sessionId);

        void onShareTsa(DriveRecordings.RecordingItem item);
    }

    private static class DisplayItem {
        final int type;
        final String sessionId;
        final String dataType;
        final DriveRecordings.RecordingItem recording;
        DriveRecordings.RecordingItem tsaRecording;
        DriveRecordings.RecordingItem cutOriginal; // the untouched original of a repaired chunk
        DriveRecordings.RecordingItem cutTimestamp; // its timestamp, taken at recovery
        boolean selected = false;

        DisplayItem(String sessionId, String dataType) {
            this.type = TYPE_HEADER;
            this.sessionId = sessionId;
            this.dataType = dataType;
            this.recording = null;
        }

        DisplayItem(DriveRecordings.RecordingItem recording) {
            this.type = TYPE_ITEM;
            this.sessionId = recording.mediaFile.sessionId;
            this.dataType = recording.mediaFile.dataType;
            this.recording = recording;
        }
    }

    private final List<DisplayItem> displayItems = new ArrayList<>();
    private final OnItemClickListener listener;
    private boolean selectionMode = false;

    public RecordingsAdapter(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void setFiles(List<DriveRecordings.RecordingItem> newItems) {
        Log.d(TAG, "setFiles: updating with " + (newItems != null ? newItems.size() : 0) + " items");
        displayItems.clear();
        displayItems.addAll(buildDisplayItems(newItems));
        notifyDataSetChanged();
    }

    public void removeItems(List<DriveRecordings.RecordingItem> itemsToRemove) {
        Log.d(TAG, "removeItems: removing " + itemsToRemove.size() + " items");
        for (DriveRecordings.RecordingItem item : itemsToRemove) {
            for (int i = 0; i < displayItems.size(); i++) {
                DisplayItem di = displayItems.get(i);
                if (di.type == TYPE_ITEM && di.recording.driveFile.getId().equals(item.driveFile.getId())) {
                    displayItems.remove(i);
                    break;
                }
            }
        }
        for (int i = displayItems.size() - 1; i >= 0; i--) {
            if (displayItems.get(i).type == TYPE_HEADER) {
                if (i == displayItems.size() - 1 || displayItems.get(i + 1).type == TYPE_HEADER) {
                    displayItems.remove(i);
                }
            }
        }
        notifyDataSetChanged();
    }

    public void setSelectionMode(boolean enabled) {
        this.selectionMode = enabled;
        if (!enabled) {
            for (DisplayItem item : displayItems)
                item.selected = false;
        }
        notifyAllItemsChanged();
    }

    public void selectAll(boolean selected) {
        for (DisplayItem item : displayItems) {
            if (item.type == TYPE_ITEM)
                item.selected = selected;
        }
        notifyAllItemsChanged();
        listener.onSelectionChanged(getSelectedCount());
    }

    private void notifyAllItemsChanged() {
        if (!displayItems.isEmpty()) {
            notifyItemRangeChanged(0, displayItems.size());
        }
    }

    private static List<DisplayItem> buildDisplayItems(List<DriveRecordings.RecordingItem> newItems) {
        List<DisplayItem> items = new ArrayList<>();
        if (newItems == null || newItems.isEmpty()) {
            return items;
        }

        List<DriveRecordings.RecordingItem> mediaItems = new ArrayList<>();
        Map<String, DriveRecordings.RecordingItem> tsaItemsByChunk = new HashMap<>();
        Map<String, DriveRecordings.RecordingItem> cutItemsByChunk = new HashMap<>();
        Map<String, DriveRecordings.RecordingItem> cutTsaItemsByChunk = new HashMap<>();
        for (DriveRecordings.RecordingItem item : newItems) {
            String chunkKey = buildChunkKey(item.mediaFile.sessionId, item.mediaFile.dataType,
                    item.mediaFile.sequenceNumber);
            String extension = item.mediaFile.getExtension();
            if ("tsa".equalsIgnoreCase(extension)) {
                tsaItemsByChunk.put(chunkKey, item);
            } else if ("cut".equalsIgnoreCase(extension)) {
                cutItemsByChunk.put(chunkKey, item);
            } else if ("cut.tsa".equalsIgnoreCase(extension)) {
                cutTsaItemsByChunk.put(chunkKey, item);
            } else if ("mp4".equalsIgnoreCase(extension)) {
                mediaItems.add(item);
            }
            // anything else is not a recording: not listed
        }

        String currentGroupKey = null;
        for (DriveRecordings.RecordingItem item : mediaItems) {
            String nextGroupKey = item.mediaFile.sessionId + "|" + item.mediaFile.dataType;
            if (currentGroupKey == null || !currentGroupKey.equals(nextGroupKey)) {
                currentGroupKey = nextGroupKey;
                items.add(new DisplayItem(item.mediaFile.sessionId, item.mediaFile.dataType));
            }
            DisplayItem displayItem = new DisplayItem(item);
            String chunkKey = buildChunkKey(item.mediaFile.sessionId, item.mediaFile.dataType,
                    item.mediaFile.sequenceNumber);
            displayItem.tsaRecording = tsaItemsByChunk.get(chunkKey);
            displayItem.cutOriginal = cutItemsByChunk.get(chunkKey);
            displayItem.cutTimestamp = cutTsaItemsByChunk.get(chunkKey);
            items.add(displayItem);
        }
        return items;
    }

    public int getSelectedCount() {
        int count = 0;
        for (DisplayItem item : displayItems) {
            if (item.type == TYPE_ITEM && item.selected)
                count++;
        }
        return count;
    }

    public List<DriveRecordings.RecordingItem> getSelectedItems() {
        List<DriveRecordings.RecordingItem> selected = new ArrayList<>();
        for (DisplayItem item : displayItems) {
            if (item.type == TYPE_ITEM && item.selected) {
                selected.add(item.recording);
                if (item.tsaRecording != null) {
                    selected.add(item.tsaRecording);
                }
                if (item.cutOriginal != null) {
                    selected.add(item.cutOriginal); // trashed with its chunk
                }
                if (item.cutTimestamp != null) {
                    selected.add(item.cutTimestamp);
                }
            }
        }
        return selected;
    }

    public List<DriveRecordings.RecordingItem> getItemsBySession(String sessionId) {
        List<DriveRecordings.RecordingItem> items = new ArrayList<>();
        for (DisplayItem item : displayItems) {
            if (item.type == TYPE_ITEM && item.sessionId.equals(sessionId)) {
                items.add(item.recording);
            }
        }
        return items;
    }

    @Override
    public int getItemViewType(int position) {
        return displayItems.get(position).type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_session_header, parent, false);
            return new HeaderViewHolder(view);
        } else {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_recording, parent, false);
            return new RecordingViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        DisplayItem displayItem = displayItems.get(position);
        if (holder instanceof HeaderViewHolder) {
            String headerText = displayItem.sessionId;
            try {
                long ts = Long.parseLong(displayItem.sessionId);
                String dateTime = DateFormat.format("yyyy-MM-dd HH:mm:ss", ts).toString();
                String type = "video".equalsIgnoreCase(displayItem.dataType)
                        ? holder.itemView.getContext().getString(R.string.video)
                        : holder.itemView.getContext().getString(R.string.audio);
                headerText = dateTime + " - " + type;
            } catch (NumberFormatException e) {
                Log.e(TAG, "Invalid session ID: " + displayItem.sessionId);
            }
            ((HeaderViewHolder) holder).sessionIdText.setText(headerText);
            ((HeaderViewHolder) holder).btnMerge
                    .setOnClickListener(v -> listener.onMergeSession(displayItem.sessionId));
        } else if (holder instanceof RecordingViewHolder) {
            RecordingViewHolder recordingHolder = (RecordingViewHolder) holder;
            DriveRecordings.RecordingItem recording = displayItem.recording;
            File file = recording.driveFile;

            // dataType: sequenceNumber
            String name = recordingHolder.itemView.getContext().getString(R.string.chunk_name,
                    String.valueOf(recording.mediaFile.sequenceNumber));
            if (displayItem.cutOriginal != null) {
                name += " " + recordingHolder.itemView.getContext().getString(R.string.chunk_repaired);
            }
            recordingHolder.fileName.setText(name);
            recordingHolder.fileDate.setText(DateFormat.format("HH:mm:ss", recording.mediaFile.timestamp));

            if (file.getSize() != null) {
                recordingHolder.fileSize
                        .setText(Formatter.formatFileSize(recordingHolder.itemView.getContext(), file.getSize()));
            } else {
                recordingHolder.fileSize.setText("");
            }

            if ("audio".equalsIgnoreCase(recording.mediaFile.dataType)) {
                recordingHolder.thumbnail.setImageResource(R.drawable.baseline_mic_24);
            } else {
                recordingHolder.thumbnail.setImageResource(R.drawable.baseline_videocam_24);
            }

            recordingHolder.checkBox.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            recordingHolder.checkBox.setOnCheckedChangeListener(null);
            recordingHolder.checkBox.setChecked(displayItem.selected);
            recordingHolder.checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                displayItem.selected = isChecked;
                listener.onSelectionChanged(getSelectedCount());
            });

            if (displayItem.tsaRecording != null) {
                recordingHolder.btnTsa.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
                recordingHolder.btnTsa.setOnClickListener(v -> listener.onShareTsa(displayItem.tsaRecording));
            } else {
                recordingHolder.btnTsa.setVisibility(View.GONE);
                recordingHolder.btnTsa.setOnClickListener(null);
            }

            recordingHolder.btnOpen.setOnClickListener(v -> listener.onItemClick(file));
            recordingHolder.itemView.setOnClickListener(v -> {
                if (selectionMode) {
                    displayItem.selected = !displayItem.selected;
                    notifyItemChanged(position);
                    listener.onSelectionChanged(getSelectedCount());
                } else {
                    listener.onItemClick(file);
                }
            });

            recordingHolder.itemView.setOnLongClickListener(v -> {
                if (!selectionMode) {
                    setSelectionMode(true);
                    displayItem.selected = true;
                    notifyItemChanged(position);
                    listener.onSelectionChanged(1);
                    return true;
                }
                return false;
            });
        }
    }

    @Override
    public int getItemCount() {
        return displayItems.size();
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        TextView sessionIdText;
        ImageButton btnMerge;

        HeaderViewHolder(View itemView) {
            super(itemView);
            sessionIdText = itemView.findViewById(R.id.session_id);
            btnMerge = itemView.findViewById(R.id.btn_merge);
        }
    }

    static class RecordingViewHolder extends RecyclerView.ViewHolder {
        CheckBox checkBox;
        ImageView thumbnail;
        TextView fileName;
        TextView fileDate;
        TextView fileSize;
        ImageButton btnOpen;
        View btnTsa;

        RecordingViewHolder(View itemView) {
            super(itemView);
            checkBox = itemView.findViewById(R.id.checkbox);
            thumbnail = itemView.findViewById(R.id.thumbnail);
            fileName = itemView.findViewById(R.id.file_name);
            fileDate = itemView.findViewById(R.id.file_date);
            fileSize = itemView.findViewById(R.id.file_size);
            btnOpen = itemView.findViewById(R.id.btn_open);
            btnTsa = itemView.findViewById(R.id.btn_tsa);
        }
    }

    private static String buildChunkKey(String sessionId, String dataType, int sequenceNumber) {
        return sessionId + "|" + dataType + "|" + sequenceNumber;
    }
}
