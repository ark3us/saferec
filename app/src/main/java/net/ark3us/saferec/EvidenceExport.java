package net.ark3us.saferec;

import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import net.ark3us.saferec.media.MediaMerger;
import net.ark3us.saferec.net.DriveRecordings;
import net.ark3us.saferec.net.EvidencePackage;
import net.ark3us.saferec.net.SessionCheck;
import net.ark3us.saferec.net.SessionManifest;
import net.ark3us.saferec.net.SessionSummary;
import net.ark3us.saferec.net.TsaClient;
import net.ark3us.saferec.services.SafeRecService;
import net.ark3us.saferec.ui.DriveTokenRefresh;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Builds the evidence package of a session and shares it, after checking it on the phone.
 * Moved out of the recordings list unchanged in behavior: the package's layout, the check and
 * the report are described in AGENTS.md.
 */
public class EvidenceExport {

    /** How an export ends, for the screen that runs it. Main thread. */
    public interface Listener {
        /** Shared, cancelled by the user, or failed with its message already shown. */
        void onDone();

        /** Drive refused the token while downloading: nothing shown, the screen may refresh it and export again. */
        void onUnauthorized(Exception e);
    }

    private static final String TAG = EvidenceExport.class.getSimpleName();
    private static final Comparator<DriveRecordings.RecordingItem> MERGE_ORDER =
            Comparator.comparingInt(item -> item.mediaFile.sequenceNumber);

    private final AppCompatActivity activity;
    private final DriveRecordings drive;
    private final ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public EvidenceExport(AppCompatActivity activity, DriveRecordings drive, ExecutorService executor) {
        this.activity = activity;
        this.drive = drive;
        this.executor = executor;
    }

    /** Same as {@link #export(SessionSummary, Listener)} with no end to wait for. */
    public void export(SessionSummary summary) {
        export(summary, new Listener() {
            @Override
            public void onDone() {
            }

            @Override
            public void onUnauthorized(Exception e) {
                Toast.makeText(activity, activity.getString(R.string.download_failed, e.getMessage()),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * Builds the evidence package of a session (see EvidencePackage) and shares it: every file
     * of the session as it is on Drive, a merged video, a README, a report and the verifier.
     */
    public void export(SessionSummary summary, Listener listener) {
        Runnable done = listener::onDone;
        String sessionId = summary.sessionId;
        String dataType = summary.dataType;
        List<DriveRecordings.RecordingItem> files = summary.files;
        SessionManifest.Gaps gaps = summary.gaps;
        if (files.isEmpty()) {
            done.run();
            return;
        }
        Log.i(TAG, "Exporting the evidence of session " + sessionId + " (" + files.size() + " files)");
        Toast.makeText(activity, R.string.exporting_evidence, Toast.LENGTH_SHORT).show();
        drive.downloadFiles(files, new DriveRecordings.Callback<List<File>>() {
            @Override
            public void onSuccess(List<File> localFiles) {
                executor.execute(() -> {
                    File merged = null;
                    try {
                        File shareDir = new File(activity.getCacheDir(), "merged_sharing");
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
                        try (java.io.InputStream in = activity.getAssets().open("verify_session.py")) {
                            verifier = readAllBytes(in);
                        }
                        File zip = new File(shareDir, EvidencePackage.rootName(sessionId) + ".zip");
                        EvidencePackage.write(zip, sessionId, dataType,
                                activity.getString(R.string.evidence_readme, formatTime(Long.parseLong(sessionId)), sessionId),
                                report, merged, evidence, verifier);
                        Log.i(TAG, "Evidence package ready: " + zip.getName() + ", " + zip.length() + " bytes");
                        Uri contentUri = FileProvider.getUriForFile(activity,
                                activity.getPackageName() + ".fileprovider", zip);
                        mainHandler.post(() -> {
                            done.run();
                            offerEvidence(check, onPhone, zip, contentUri);
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "Evidence package failed", e);
                        mainHandler.post(() -> {
                            done.run();
                            Toast.makeText(activity, activity.getString(R.string.export_failed, e.getMessage()),
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
                    if (DriveTokenRefresh.isUnauthorized(e)) {
                        listener.onUnauthorized(e);
                        return;
                    }
                    done.run();
                    Toast.makeText(activity, activity.getString(R.string.download_failed, e.getMessage()),
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
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (check.ok()) {
            Toast.makeText(activity, R.string.check_verified, Toast.LENGTH_SHORT).show();
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
            message.append('\n').append(activity.getString(R.string.check_dialog_on_phone));
        } else if (missing) {
            message.append('\n').append(activity.getString(R.string.check_dialog_refresh));
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.check_dialog_title)
                .setMessage(message.toString().trim())
                .setPositiveButton(R.string.check_share_anyway, (d, which) -> shareEvidence(contentUri))
                .setNegativeButton(android.R.string.cancel, (d, which) -> zip.delete())
                .setOnCancelListener(d -> zip.delete());
        if (stillHere) {
            dialog.setNeutralButton(R.string.check_upload_now, (d, which) -> {
                zip.delete();
                activity.startService(SafeRecService.createCommandIntent(activity, SafeRecService.CMD_UPLOAD_PENDING));
            });
        }
        dialog.show();
    }

    private void shareEvidence(Uri contentUri) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_STREAM, contentUri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.share_evidence)));
    }

    /** One finding of the check, in the app's language. */
    private String describe(SessionCheck.Finding finding, Set<Integer> onPhone) {
        String text;
        switch (finding.kind) {
            case NO_MANIFEST:
                text = activity.getString(R.string.check_no_manifest);
                break;
            case EMPTY_MANIFEST:
                text = activity.getString(R.string.check_empty_manifest);
                break;
            case NEVER_CLOSED:
                text = activity.getString(R.string.check_never_closed, String.valueOf(finding.seq));
                break;
            case MISSING:
                text = activity.getString(R.string.check_missing, finding.file, finding.seq);
                break;
            case ALTERED:
                text = activity.getString(R.string.check_altered, finding.file, finding.seq);
                break;
            case TIMESTAMP_INVALID:
                text = activity.getString(R.string.check_timestamp_invalid, finding.file);
                break;
            case MANIFEST_EDITED:
                text = activity.getString(R.string.check_manifest_edited);
                break;
            case MANIFEST_UNTIMESTAMPED:
                text = activity.getString(R.string.check_manifest_untimestamped);
                break;
            case ADDED_AFTER_TIMESTAMP:
                text = activity.getString(R.string.check_added_later, String.valueOf(finding.seq));
                break;
            case CUT:
                text = activity.getString(R.string.check_cut, String.valueOf(finding.seq));
                break;
            default:
                text = activity.getString(R.string.check_not_in_manifest, finding.file);
                break;
        }
        boolean gap = finding.kind == SessionCheck.Kind.MISSING || finding.kind == SessionCheck.Kind.NEVER_CLOSED;
        return gap && onPhone.contains(finding.seq) ? text + " " + activity.getString(R.string.check_still_on_phone) : text;
    }

    /** The check's verdict for the report: what this phone found when it made the package. */
    private String checkSummary(SessionCheck check, Set<Integer> onPhone) {
        StringBuilder text = new StringBuilder(activity.getString(R.string.report_check)).append(": ");
        text.append(check.ok() ? activity.getString(R.string.report_check_complete, check.checkedFiles, check.checkedTimestamps)
                : activity.getString(R.string.report_check_failed));
        for (SessionCheck.Finding problem : check.problems()) {
            text.append("\n  - ").append(describe(problem, onPhone));
        }
        for (SessionCheck.Finding note : check.notes()) {
            text.append("\n  - ").append(activity.getString(R.string.report_check_note)).append(": ").append(describe(note, onPhone));
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
                note = activity.getString(R.string.report_note_mismatch);
            } else if (byName.containsKey(base + ".cut")) {
                note = activity.getString(R.string.report_note_repaired, base + ".cut");
            }
            rows.add(new EvidencePackage.Row(chunk.mediaFile.sequenceNumber, name, file.length(),
                    SessionManifest.hex(hash), stamp != null ? formatUtc(stamp.time.getTime()) : null, note));
        }
        String manifestState;
        if (check.has(SessionCheck.Kind.NO_MANIFEST)) {
            manifestState = activity.getString(R.string.report_manifest_missing);
        } else if (check.has(SessionCheck.Kind.EMPTY_MANIFEST)) {
            manifestState = activity.getString(R.string.check_empty_manifest);
        } else if (check.has(SessionCheck.Kind.MANIFEST_UNTIMESTAMPED)) {
            manifestState = activity.getString(R.string.report_manifest_untimestamped);
        } else if (check.manifestTime == null) {
            manifestState = activity.getString(R.string.report_manifest_invalid);
        } else if (check.has(SessionCheck.Kind.ADDED_AFTER_TIMESTAMP)) {
            manifestState = activity.getString(R.string.report_manifest_later, formatUtc(check.manifestTime.getTime()));
        } else {
            manifestState = activity.getString(R.string.report_manifest_timestamped, formatUtc(check.manifestTime.getTime()));
        }
        List<String> gapLines = new ArrayList<>();
        if (gaps != null) {
            for (Integer seq : gaps.lost) {
                gapLines.add(activity.getString(R.string.report_gaps_lost, String.valueOf(seq)));
            }
            for (Integer seq : gaps.onPhone) {
                gapLines.add(activity.getString(R.string.report_gaps_on_phone, String.valueOf(seq)));
            }
        }
        EvidencePackage.Labels labels = new EvidencePackage.Labels();
        labels.title = activity.getString(R.string.report_title);
        labels.started = activity.getString(R.string.report_started);
        labels.type = activity.getString(R.string.report_type);
        labels.chunks = activity.getString(R.string.report_chunks);
        labels.duration = activity.getString(R.string.report_duration);
        labels.manifest = activity.getString(R.string.report_manifest);
        labels.gaps = activity.getString(R.string.report_gaps);
        labels.none = activity.getString(R.string.report_none);
        labels.columns = activity.getString(R.string.report_columns);
        labels.noTimestamp = activity.getString(R.string.report_no_timestamp);
        labels.footer = activity.getString(R.string.report_footer);
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

}
