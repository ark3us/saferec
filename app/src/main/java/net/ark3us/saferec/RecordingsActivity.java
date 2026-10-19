package net.ark3us.saferec;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
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
import java.util.Map;
import java.util.HashMap;
import net.ark3us.saferec.net.TsaClient;
import net.ark3us.saferec.net.SessionManifest;
import net.ark3us.saferec.net.EvidencePackage;
import net.ark3us.saferec.net.SessionCheck;
import net.ark3us.saferec.misc.Settings;
import net.ark3us.saferec.net.DriveRecordings;
import net.ark3us.saferec.services.SafeRecService;
import net.ark3us.saferec.ui.TutorialOverlayView;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RecordingsActivity extends AppCompatActivity {

    private static final String TAG = RecordingsActivity.class.getSimpleName();
    private static final Comparator<DriveRecordings.RecordingItem> MERGE_ORDER =
            Comparator.comparingInt(item -> item.mediaFile.sequenceNumber);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private RecyclerView recyclerView;
    private RecordingsAdapter adapter;
    private ProgressBar progressBar;
    private SwipeRefreshLayout swipeRefreshLayout;
    private TextView emptyState;
    private View selectionToolbar;
    private TextView selectionCountText;
    private CheckBox selectAllCheckbox;
    private DriveRecordings drive;

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
            public void onExportSession(String sessionId, String dataType) {
                exportSession(sessionId, dataType);
            }

            @Override
            public void onShareTsa(DriveRecordings.RecordingItem item) {
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
            drive = new DriveRecordings(this, accessToken);
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
        } else if (item.getItemId() == R.id.action_tutorial) {
            showTutorialOverlay(-1);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateSelectionUI(int count) {
        if (count > 0) {
            selectionToolbar.setVisibility(View.VISIBLE);
            selectionCountText.setText(getResources().getQuantityString(R.plurals.selection_count, count, count));
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
        List<DriveRecordings.RecordingItem> selected = adapter.getSelectedItems();
        if (selected.isEmpty())
            return;

        List<String> fileIds = new ArrayList<>();
        Set<String> folderIds = new HashSet<>();
        for (DriveRecordings.RecordingItem item : selected) {
            fileIds.add(item.driveFile.getId());
            if (item.driveFile.getParents() != null) {
                folderIds.addAll(item.driveFile.getParents());
            }
        }

        Intent intent = SafeRecService.createCommandIntent(this, SafeRecService.CMD_DELETE);
        intent.putExtra(SafeRecService.EXTRA_FILE_IDS, fileIds.toArray(new String[0]));
        intent.putExtra(SafeRecService.EXTRA_FOLDER_IDS, folderIds.toArray(new String[0]));
        startService(intent); // the activity is in front: a plain start is allowed

        // Optimistic UI update
        adapter.removeItems(selected);
        exitSelectionMode();
        if (adapter.getItemCount() == 0) {
            emptyState.setVisibility(View.VISIBLE);
            emptyState.setText(R.string.no_recordings_found);
        }
        Toast.makeText(this,
                getResources().getQuantityString(R.plurals.deleting_recordings_bg, selected.size(), selected.size()),
                Toast.LENGTH_SHORT).show();
    }

    private void shareSelected() {
        List<DriveRecordings.RecordingItem> selected = adapter.getSelectedItems();
        if (selected.isEmpty())
            return;

        progressBar.setVisibility(View.VISIBLE);
        drive.shareFiles(selected, new DriveRecordings.Callback<List<Uri>>() {
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
                    startActivity(Intent.createChooser(intent, getString(R.string.share_recording)));
                });
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "Share failed", e);
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(RecordingsActivity.this, getString(R.string.share_failed, e.getMessage()), Toast.LENGTH_LONG)
                            .show();
                });
            }
        });
    }

    private void shareTsaFile(DriveRecordings.RecordingItem item) {
        List<DriveRecordings.RecordingItem> single = new ArrayList<>();
        single.add(item);
        progressBar.setVisibility(View.VISIBLE);
        drive.shareFiles(single, new DriveRecordings.Callback<List<Uri>>() {
            @Override
            public void onSuccess(List<Uri> uris) {
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("application/timestamp-reply");
                    intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(intent, getString(R.string.share_tsa_signature)));
                });
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "Share TSA failed", e);
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(RecordingsActivity.this, getString(R.string.share_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void loadRecordings() {
        if (!swipeRefreshLayout.isRefreshing()) {
            progressBar.setVisibility(View.VISIBLE);
        }
        drive.listRecordings(new DriveRecordings.Callback<List<DriveRecordings.RecordingItem>>() {
            @Override
            public void onSuccess(List<DriveRecordings.RecordingItem> result) {
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

                    if (!Settings.isRecordingsTutorialShown(RecordingsActivity.this)) {
                        mainHandler.postDelayed(() -> showTutorialOverlay(-1), 500);
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
            Toast.makeText(this, R.string.session_expired, Toast.LENGTH_SHORT).show();
        }
    }

    private void mergeSession(String sessionId) {
        List<DriveRecordings.RecordingItem> sessionItems = adapter.getItemsBySession(sessionId);
        if (sessionItems.isEmpty())
            return;
        sessionItems.sort(MERGE_ORDER);
        Log.i(TAG, "Merging session " + sessionId + " with " + sessionItems.size() + " chunks");

        progressBar.setVisibility(View.VISIBLE);
        Toast.makeText(this, R.string.downloading_chunks, Toast.LENGTH_SHORT).show();

        drive.downloadFiles(sessionItems, new DriveRecordings.Callback<List<File>>() {
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
                            startActivity(Intent.createChooser(intent, getString(R.string.share_merged_recording)));
                        });

                    } catch (Exception e) {
                        Log.e(TAG, "Merge failed", e);
                        mainHandler.post(() -> {
                            progressBar.setVisibility(View.GONE);
                            Toast.makeText(RecordingsActivity.this, getString(R.string.merge_failed, e.getMessage()),
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
                    Toast.makeText(RecordingsActivity.this, getString(R.string.download_failed, e.getMessage()), Toast.LENGTH_LONG)
                            .show();
                });
            }
        });
    }

    /**
     * Builds the evidence package of a session (see EvidencePackage) and shares it: every file
     * of the session as it is on Drive, a merged video, a README, a report and the verifier.
     */
    private void exportSession(String sessionId, String dataType) {
        List<DriveRecordings.RecordingItem> files = adapter.getSessionFiles(sessionId, dataType);
        SessionManifest.Gaps gaps = adapter.getGaps(sessionId, dataType);
        if (files.isEmpty()) {
            return;
        }
        Log.i(TAG, "Exporting the evidence of session " + sessionId + " (" + files.size() + " files)");
        progressBar.setVisibility(View.VISIBLE);
        Toast.makeText(this, R.string.exporting_evidence, Toast.LENGTH_SHORT).show();
        drive.downloadFiles(files, new DriveRecordings.Callback<List<File>>() {
            @Override
            public void onSuccess(List<File> localFiles) {
                backgroundExecutor.execute(() -> {
                    File merged = null;
                    try {
                        File shareDir = new File(getCacheDir(), "merged_sharing");
                        if (!shareDir.isDirectory() && !shareDir.mkdirs()) {
                            throw new IllegalStateException("Failed to create share dir: " + shareDir.getAbsolutePath());
                        }
                        Map<String, File> byName = new HashMap<>();
                        List<EvidencePackage.Item> evidence = new ArrayList<>();
                        List<DriveRecordings.RecordingItem> chunks = new ArrayList<>();
                        for (int i = 0; i < files.size(); i++) {
                            DriveRecordings.RecordingItem item = files.get(i);
                            byName.put(item.driveFile.getName(), localFiles.get(i));
                            evidence.add(new EvidencePackage.Item(localFiles.get(i), item.driveFile.getName()));
                            if ("mp4".equalsIgnoreCase(item.mediaFile.getExtension())) {
                                chunks.add(item);
                            }
                        }
                        chunks.sort(MERGE_ORDER);
                        List<File> chunkFiles = new ArrayList<>();
                        for (DriveRecordings.RecordingItem chunk : chunks) {
                            chunkFiles.add(byName.get(chunk.driveFile.getName()));
                        }
                        String duration = "-";
                        if (!chunkFiles.isEmpty()) {
                            try {
                                merged = new File(shareDir, "evidence_merged_" + sessionId + ".mp4");
                                MediaMerger.merge(chunkFiles, merged);
                                duration = durationOf(merged);
                            } catch (Exception e) {
                                // The chunks are the evidence: the package goes without the merged video
                                Log.e(TAG, "Merge for the evidence package failed", e);
                                if (merged != null) {
                                    merged.delete();
                                }
                                merged = null;
                            }
                        }
                        // The verifier's checks, now: a chunk missing on Drive may still be on this phone
                        SessionCheck check = SessionCheck.run(sessionId, byName, SessionCheck.systemRoots());
                        Set<Integer> onPhone = drive.chunksOnThisPhone(sessionId, dataType);
                        Log.i(TAG, "Session check of " + sessionId + ": " + (check.ok() ? "COMPLETE" : "NOT VERIFIED") + ", "
                                + check.checkedFiles + " files and " + check.checkedTimestamps + " timestamps valid"
                                + (check.findings.isEmpty() ? "" : ", " + check.findings));
                        String report = evidenceReport(sessionId, dataType, chunks, byName, gaps, duration, check, onPhone);
                        byte[] verifier;
                        try (java.io.InputStream in = getAssets().open("verify_session.py")) {
                            verifier = readAllBytes(in);
                        }
                        EvidencePackage.deleteOld(shareDir, 24 * 60 * 60 * 1000L, System.currentTimeMillis());
                        File zip = new File(shareDir, EvidencePackage.rootName(sessionId) + ".zip");
                        EvidencePackage.write(zip, sessionId, dataType,
                                getString(R.string.evidence_readme, formatTime(Long.parseLong(sessionId)), sessionId),
                                report, merged, evidence, verifier);
                        Log.i(TAG, "Evidence package ready: " + zip.getName() + ", " + zip.length() + " bytes");
                        Uri contentUri = FileProvider.getUriForFile(RecordingsActivity.this,
                                getPackageName() + ".fileprovider", zip);
                        mainHandler.post(() -> {
                            progressBar.setVisibility(View.GONE);
                            offerEvidence(check, onPhone, zip, contentUri);
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "Evidence package failed", e);
                        mainHandler.post(() -> {
                            progressBar.setVisibility(View.GONE);
                            Toast.makeText(RecordingsActivity.this, getString(R.string.export_failed, e.getMessage()),
                                    Toast.LENGTH_LONG).show();
                        });
                    } finally {
                        for (File f : localFiles) {
                            f.delete();
                        }
                        if (merged != null) {
                            merged.delete();
                        }
                    }
                });
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "Download for the evidence package failed", e);
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(RecordingsActivity.this, getString(R.string.download_failed, e.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /**
     * Shares a verified package at once. Otherwise says what is wrong first, while it may still
     * be fixed: a chunk still on this phone can be uploaded, and the package made again.
     */
    private void offerEvidence(SessionCheck check, Set<Integer> onPhone, File zip, Uri contentUri) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (check.ok()) {
            Toast.makeText(this, R.string.check_verified, Toast.LENGTH_SHORT).show();
            shareEvidence(contentUri);
            return;
        }
        StringBuilder message = new StringBuilder();
        boolean missing = false;
        boolean stillHere = false;
        for (SessionCheck.Finding problem : check.problems()) {
            message.append("• ").append(describe(problem, onPhone)).append('\n');
            if (problem.kind == SessionCheck.Kind.MISSING || problem.kind == SessionCheck.Kind.NEVER_CLOSED) {
                missing = true;
                stillHere |= onPhone.contains(problem.seq);
            }
        }
        if (stillHere) {
            message.append('\n').append(getString(R.string.check_dialog_on_phone));
        } else if (missing) {
            message.append('\n').append(getString(R.string.check_dialog_refresh));
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.check_dialog_title)
                .setMessage(message.toString().trim())
                .setPositiveButton(R.string.check_share_anyway, (d, which) -> shareEvidence(contentUri))
                .setNegativeButton(android.R.string.cancel, (d, which) -> zip.delete())
                .setOnCancelListener(d -> zip.delete());
        if (stillHere) {
            dialog.setNeutralButton(R.string.check_upload_now, (d, which) -> {
                zip.delete();
                startService(SafeRecService.createCommandIntent(this, SafeRecService.CMD_UPLOAD_PENDING));
            });
        }
        dialog.show();
    }

    private void shareEvidence(Uri contentUri) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_STREAM, contentUri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, getString(R.string.share_evidence)));
    }

    /** One finding of the check, in the app's language. */
    private String describe(SessionCheck.Finding finding, Set<Integer> onPhone) {
        String text;
        switch (finding.kind) {
            case NO_MANIFEST:
                text = getString(R.string.check_no_manifest);
                break;
            case EMPTY_MANIFEST:
                text = getString(R.string.check_empty_manifest);
                break;
            case NEVER_CLOSED:
                text = getString(R.string.check_never_closed, String.valueOf(finding.seq));
                break;
            case MISSING:
                text = getString(R.string.check_missing, finding.file, finding.seq);
                break;
            case ALTERED:
                text = getString(R.string.check_altered, finding.file, finding.seq);
                break;
            case TIMESTAMP_INVALID:
                text = getString(R.string.check_timestamp_invalid, finding.file);
                break;
            case MANIFEST_EDITED:
                text = getString(R.string.check_manifest_edited);
                break;
            case MANIFEST_UNTIMESTAMPED:
                text = getString(R.string.check_manifest_untimestamped);
                break;
            case ADDED_AFTER_TIMESTAMP:
                text = getString(R.string.check_added_later, String.valueOf(finding.seq));
                break;
            case CUT:
                text = getString(R.string.check_cut, String.valueOf(finding.seq));
                break;
            default:
                text = getString(R.string.check_not_in_manifest, finding.file);
                break;
        }
        boolean gap = finding.kind == SessionCheck.Kind.MISSING || finding.kind == SessionCheck.Kind.NEVER_CLOSED;
        return gap && onPhone.contains(finding.seq) ? text + " " + getString(R.string.check_still_on_phone) : text;
    }

    /** The check's verdict for the report: what this phone found when it made the package. */
    private String checkSummary(SessionCheck check, Set<Integer> onPhone) {
        StringBuilder text = new StringBuilder(getString(R.string.report_check)).append(": ");
        text.append(check.ok() ? getString(R.string.report_check_complete, check.checkedFiles, check.checkedTimestamps)
                : getString(R.string.report_check_failed));
        for (SessionCheck.Finding problem : check.problems()) {
            text.append("\n  - ").append(describe(problem, onPhone));
        }
        for (SessionCheck.Finding note : check.notes()) {
            text.append("\n  - ").append(getString(R.string.report_check_note)).append(": ").append(describe(note, onPhone));
        }
        return text.toString();
    }

    private String evidenceReport(String sessionId, String dataType, List<DriveRecordings.RecordingItem> chunks,
            Map<String, File> byName, SessionManifest.Gaps gaps, String duration, SessionCheck check, Set<Integer> onPhone) {
        List<EvidencePackage.Row> rows = new ArrayList<>();
        for (DriveRecordings.RecordingItem chunk : chunks) {
            String name = chunk.driveFile.getName();
            File file = byName.get(name);
            byte[] hash = check.sha256(name);
            String base = name.substring(0, name.lastIndexOf('.'));
            File token = byName.get(base + ".tsa");
            TsaClient.Info stamp = token != null ? TsaClient.read(token) : null;
            String note = null;
            if (stamp != null && hash != null && !java.util.Arrays.equals(stamp.imprint, hash)) {
                note = getString(R.string.report_note_mismatch);
            } else if (byName.containsKey(base + ".cut")) {
                note = getString(R.string.report_note_repaired, base + ".cut");
            }
            rows.add(new EvidencePackage.Row(chunk.mediaFile.sequenceNumber, name, file.length(),
                    SessionManifest.hex(hash), stamp != null ? formatUtc(stamp.time.getTime()) : null, note));
        }
        String manifestState;
        if (check.has(SessionCheck.Kind.NO_MANIFEST)) {
            manifestState = getString(R.string.report_manifest_missing);
        } else if (check.has(SessionCheck.Kind.EMPTY_MANIFEST)) {
            manifestState = getString(R.string.check_empty_manifest);
        } else if (check.has(SessionCheck.Kind.MANIFEST_UNTIMESTAMPED)) {
            manifestState = getString(R.string.report_manifest_untimestamped);
        } else if (check.manifestTime == null) {
            manifestState = getString(R.string.report_manifest_invalid);
        } else if (check.has(SessionCheck.Kind.ADDED_AFTER_TIMESTAMP)) {
            manifestState = getString(R.string.report_manifest_later, formatUtc(check.manifestTime.getTime()));
        } else {
            manifestState = getString(R.string.report_manifest_timestamped, formatUtc(check.manifestTime.getTime()));
        }
        List<String> gapLines = new ArrayList<>();
        if (gaps != null) {
            for (Integer seq : gaps.lost) {
                gapLines.add(getString(R.string.report_gaps_lost, String.valueOf(seq)));
            }
            for (Integer seq : gaps.onPhone) {
                gapLines.add(getString(R.string.report_gaps_on_phone, String.valueOf(seq)));
            }
        }
        EvidencePackage.Labels labels = new EvidencePackage.Labels();
        labels.title = getString(R.string.report_title);
        labels.started = getString(R.string.report_started);
        labels.type = getString(R.string.report_type);
        labels.chunks = getString(R.string.report_chunks);
        labels.duration = getString(R.string.report_duration);
        labels.manifest = getString(R.string.report_manifest);
        labels.gaps = getString(R.string.report_gaps);
        labels.none = getString(R.string.report_none);
        labels.columns = getString(R.string.report_columns);
        labels.noTimestamp = getString(R.string.report_no_timestamp);
        labels.footer = getString(R.string.report_footer);
        return EvidencePackage.report(labels, sessionId, dataType, formatTime(Long.parseLong(sessionId)),
                duration, manifestState, String.join("; ", gapLines), checkSummary(check, onPhone), rows);
    }

    /** Local time with its zone, and UTC: "2026-10-19 19:09:06 CEST (17:09:06 UTC)". */
    private static String formatTime(long millis) {
        java.text.SimpleDateFormat local = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz", java.util.Locale.ROOT);
        return local.format(new java.util.Date(millis)) + " (" + formatUtc(millis) + ")";
    }

    private static String formatUtc(long millis) {
        java.text.SimpleDateFormat utc = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", java.util.Locale.ROOT);
        utc.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return utc.format(new java.util.Date(millis));
    }

    private static String durationOf(File video) {
        android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
        try {
            retriever.setDataSource(video.getAbsolutePath());
            String ms = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            long seconds = ms != null ? Long.parseLong(ms) / 1000 : 0;
            return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
        } catch (Exception e) {
            return "-";
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static byte[] readAllBytes(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    @Override
    protected void onDestroy() {
        if (drive != null) {
            drive.shutdown();
        }
        backgroundExecutor.shutdownNow();
        super.onDestroy();
    }

    private void showTutorialOverlay(int step) {
        View target = null;
        CharSequence title = null;
        CharSequence message = "";

        switch (step) {
            case -1:
                title = getText(R.string.tutorial_recordings_title);
                message = getText(R.string.tutorial_recordings_message);
                break;
            case 0:
                // View recording
                target = recyclerView.getChildAt(0);
                message = getText(R.string.tutorial_recordings_step_view);
                break;
            case 1:
                // TSA info
                target = recyclerView.getChildAt(0);
                if (target != null) {
                    target = target.findViewById(R.id.btn_tsa);
                }
                message = getText(R.string.tutorial_recordings_step_tsa);
                break;
            case 2:
                // Merge session
                target = recyclerView.getChildAt(0);
                if (target != null) {
                    // Try to find if the first item is a header or has a merge button
                    View header = recyclerView.findViewById(R.id.btn_merge);
                    if (header != null) target = header;
                }
                message = getText(R.string.tutorial_recordings_step_merge);
                break;
            case 3:
                // Selection mode
                target = recyclerView.getChildAt(0);
                message = getText(R.string.tutorial_recordings_step_selection);
                break;
            default:
                // End of tutorial
                View overlay = findViewById(R.id.tutorial_overlay);
                if (overlay != null) {
                    ((ViewGroup) overlay.getParent()).removeView(overlay);
                }
                Settings.setRecordingsTutorialShown(this, true);
                return;
        }

        TutorialOverlayView overlay = findViewById(R.id.tutorial_overlay);
        if (overlay == null) {
            overlay = new TutorialOverlayView(this);
            overlay.setId(R.id.tutorial_overlay);
            addContentView(overlay, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }

        final int nextStep = step + 1;
        overlay.setTarget(target, title, message, () -> showTutorialOverlay(nextStep));
    }
}
