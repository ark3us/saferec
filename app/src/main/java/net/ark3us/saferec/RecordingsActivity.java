package net.ark3us.saferec;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.view.Menu;
import android.view.MenuItem;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import net.ark3us.saferec.media.MediaMerger;
import net.ark3us.saferec.misc.Settings;
import net.ark3us.saferec.net.FileDownloader;
import net.ark3us.saferec.net.GoogleDriveFileDownloader;
import net.ark3us.saferec.services.SafeRecService;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RecordingsActivity extends AppCompatActivity {

    private static final String TAG = RecordingsActivity.class.getSimpleName();

    private RecyclerView recyclerView;
    private RecordingsAdapter adapter;
    private ProgressBar progressBar;
    private SwipeRefreshLayout swipeRefreshLayout;
    private TextView emptyState;
    private View selectionToolbar;
    private TextView selectionCountText;
    private CheckBox selectAllCheckbox;
    private FileDownloader downloader;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_recordings);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.recordings_root), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> {
            if (adapter != null && adapter.getSelectedCount() > 0) {
                exitSelectionMode();
            } else {
                finish();
            }
        });

        recyclerView = findViewById(R.id.recycler_view);
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        progressBar = findViewById(R.id.progress_bar);
        emptyState = findViewById(R.id.empty_state);
        selectionToolbar = findViewById(R.id.selection_toolbar);
        selectionCountText = findViewById(R.id.selection_count);
        selectAllCheckbox = findViewById(R.id.select_all);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RecordingsAdapter(new RecordingsAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(com.google.api.services.drive.model.File file) {
                playRecording(file);
            }

            @Override
            public void onSelectionChanged(int count) {
                updateSelectionUI(count);
            }

            @Override
            public void onMergeSession(String sessionId) {
                mergeSession(sessionId);
            }

            @Override
            public void onShareTsa(FileDownloader.RecordingItem item) {
                shareTsaFile(item);
            }
        });
        recyclerView.setAdapter(adapter);

        selectAllCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            adapter.selectAll(isChecked);
        });

        findViewById(R.id.btn_delete).setOnClickListener(v -> deleteSelected());
        findViewById(R.id.btn_share).setOnClickListener(v -> shareSelected());

        swipeRefreshLayout.setOnRefreshListener(this::loadRecordings);
        swipeRefreshLayout.setColorSchemeResources(R.color.primary);
        swipeRefreshLayout.setProgressBackgroundColorSchemeResource(R.color.surface_elevated);

        String accessToken = Settings.getAccessToken(this);
        if (accessToken != null) {
            downloader = new GoogleDriveFileDownloader(this, accessToken);
            loadRecordings();
        } else {
            Log.e(TAG, "No access token found");
            finish();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.recordings_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_refresh) {
            loadRecordings();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateSelectionUI(int count) {
        if (count > 0) {
            selectionToolbar.setVisibility(View.VISIBLE);
            selectionCountText.setText(
                    count == 0 ? getString(R.string.no_selection) : getString(R.string.selection_count, count));
        } else {
            exitSelectionMode();
        }
    }

    private void exitSelectionMode() {
        selectionToolbar.setVisibility(View.GONE);
        adapter.setSelectionMode(false);
        selectAllCheckbox.setChecked(false);
    }

    private void deleteSelected() {
        List<FileDownloader.RecordingItem> selected = adapter.getSelectedItems();
        if (selected.isEmpty())
            return;

        List<String> fileIds = new ArrayList<>();
        java.util.Set<String> folderIds = new java.util.HashSet<>();
        for (FileDownloader.RecordingItem item : selected) {
            fileIds.add(item.driveFile.getId());
            if (item.driveFile.getParents() != null) {
                folderIds.addAll(item.driveFile.getParents());
            }
        }

        Intent intent = SafeRecService.createCommandIntent(this, SafeRecService.CMD_DELETE);
        intent.putExtra(SafeRecService.EXTRA_FILE_IDS, fileIds.toArray(new String[0]));
        intent.putExtra(SafeRecService.EXTRA_FOLDER_IDS, folderIds.toArray(new String[0]));
        startForegroundService(intent);

        // Optimistic UI update
        adapter.removeItems(selected);
        exitSelectionMode();
        if (adapter.getItemCount() == 0) {
            emptyState.setVisibility(View.VISIBLE);
            emptyState.setText(R.string.no_recordings_found);
        }
        Toast.makeText(this, "Deleting " + selected.size() + " recordings in background...", Toast.LENGTH_SHORT).show();
    }

    private void shareSelected() {
        List<FileDownloader.RecordingItem> selected = adapter.getSelectedItems();
        if (selected.isEmpty())
            return;

        progressBar.setVisibility(View.VISIBLE);
        downloader.shareFiles(selected, new FileDownloader.Callback<List<Uri>>() {
            @Override
            public void onSuccess(List<Uri> uris) {
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    exitSelectionMode();

                    Intent intent = new Intent();
                    if (uris.size() == 1) {
                        intent.setAction(Intent.ACTION_SEND);
                        intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                    } else {
                        intent.setAction(Intent.ACTION_SEND_MULTIPLE);
                        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(uris));
                    }
                    intent.setType("*/*");
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(intent, "Share Recording"));
                });
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "Share failed", e);
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(RecordingsActivity.this, "Share failed: " + e.getMessage(), Toast.LENGTH_LONG)
                            .show();
                });
            }
        });
    }

    private void shareTsaFile(FileDownloader.RecordingItem item) {
        List<FileDownloader.RecordingItem> single = new ArrayList<>();
        single.add(item);
        progressBar.setVisibility(View.VISIBLE);
        downloader.shareFiles(single, new FileDownloader.Callback<List<Uri>>() {
            @Override
            public void onSuccess(List<Uri> uris) {
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("application/timestamp-reply");
                    intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(intent, "Share TSA Signature"));
                });
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "Share TSA failed", e);
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(RecordingsActivity.this, "Share failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void loadRecordings() {
        if (!swipeRefreshLayout.isRefreshing()) {
            progressBar.setVisibility(View.VISIBLE);
        }
        downloader.listRecordings(new FileDownloader.Callback<List<FileDownloader.RecordingItem>>() {
            @Override
            public void onSuccess(List<FileDownloader.RecordingItem> result) {
                Log.d(TAG, "Loaded " + (result != null ? result.size() : 0) + " recordings from server");
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    swipeRefreshLayout.setRefreshing(false);
                    adapter.setFiles(result);
                    if (result != null && !result.isEmpty()) {
                        emptyState.setVisibility(View.GONE);
                    } else {
                        emptyState.setVisibility(View.VISIBLE);
                        emptyState.setText(R.string.no_recordings_found);
                    }
                });
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "Failed to load recordings", e);
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    swipeRefreshLayout.setRefreshing(false);
                    emptyState.setVisibility(View.VISIBLE);
                    emptyState.setText(R.string.error_loading_recordings);
                });
            }
        });
    }

    private void playRecording(com.google.api.services.drive.model.File file) {
        String accessToken = Settings.getAccessToken(this);
        if (accessToken != null) {
            Intent intent = new Intent(this, PlaybackActivity.class);
            intent.putExtra(PlaybackActivity.EXTRA_FILE_ID, file.getId());
            intent.putExtra(PlaybackActivity.EXTRA_ACCESS_TOKEN, accessToken);
            startActivity(intent);
        } else {
            Toast.makeText(this, "Session expired, please log in again", Toast.LENGTH_SHORT).show();
        }
    }

    private void mergeSession(String sessionId) {
        List<FileDownloader.RecordingItem> sessionItems = adapter.getItemsBySession(sessionId);
        if (sessionItems.isEmpty())
            return;

        progressBar.setVisibility(View.VISIBLE);
        Toast.makeText(this, "Downloading chunks...", Toast.LENGTH_SHORT).show();

        downloader.downloadFiles(sessionItems, new FileDownloader.Callback<List<File>>() {
            @Override
            public void onSuccess(List<File> localFiles) {
                backgroundExecutor.execute(() -> {
                    try {
                        File shareDir = new File(getCacheDir(), "merged_sharing");
                        if (!shareDir.exists() && !shareDir.mkdirs()) {
                            throw new IllegalStateException("Failed to create share dir: " + shareDir.getAbsolutePath());
                        }

                        File shareFile = new File(shareDir, "merged_" + sessionId + ".mp4");
                        MediaMerger.merge(localFiles, shareFile);

                        Uri contentUri = FileProvider.getUriForFile(RecordingsActivity.this,
                                getPackageName() + ".fileprovider", shareFile);

                        mainHandler.post(() -> {
                            progressBar.setVisibility(View.GONE);
                            Intent intent = new Intent(Intent.ACTION_SEND);
                            intent.setType("video/mp4");
                            intent.putExtra(Intent.EXTRA_STREAM, contentUri);
                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(Intent.createChooser(intent, "Share Merged Recording"));
                        });

                    } catch (Exception e) {
                        Log.e(TAG, "Merge failed", e);
                        mainHandler.post(() -> {
                            progressBar.setVisibility(View.GONE);
                            Toast.makeText(RecordingsActivity.this, "Merge failed: " + e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        });
                    } finally {
                        // Cleanup chunk files
                        for (File f : localFiles)
                            f.delete();
                    }
                });
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "Download for merge failed", e);
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(RecordingsActivity.this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG)
                            .show();
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (downloader != null) {
            downloader.shutdown();
        }
        backgroundExecutor.shutdownNow();
        super.onDestroy();
    }
}
