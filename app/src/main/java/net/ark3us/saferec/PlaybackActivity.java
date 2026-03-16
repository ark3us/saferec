package net.ark3us.saferec;

import android.net.Uri;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.widget.VideoView;
import androidx.constraintlayout.widget.ConstraintLayout;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.HashMap;
import java.util.Map;

public class PlaybackActivity extends AppCompatActivity {
    private static final String TAG = "PlaybackActivity";
    public static final String EXTRA_FILE_ID = "file_id";
    public static final String EXTRA_ACCESS_TOKEN = "access_token";

    private VideoView videoView;
    private ProgressBar progressBar;
    private MediaController mediaController;
    private int preparedVideoWidth;
    private int preparedVideoHeight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_playback);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            View btnBack = findViewById(R.id.btn_back);
            if (btnBack != null) {
                ConstraintLayout.LayoutParams lp = (ConstraintLayout.LayoutParams) btnBack.getLayoutParams();
                lp.topMargin = systemBars.top + (int) (16 * getResources().getDisplayMetrics().density);
                lp.leftMargin = systemBars.left + (int) (16 * getResources().getDisplayMetrics().density);
                btnBack.setLayoutParams(lp);
            }
            return insets;
        });

        videoView = findViewById(R.id.video_view);
        progressBar = findViewById(R.id.playback_progress);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        String fileId = getIntent().getStringExtra(EXTRA_FILE_ID);
        String accessToken = getIntent().getStringExtra(EXTRA_ACCESS_TOKEN);

        if (fileId == null || accessToken == null) {
            Toast.makeText(this, R.string.error_missing_file, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        playVideo(fileId, accessToken);
    }

    private void playVideo(String fileId, String accessToken) {
        progressBar.setVisibility(View.VISIBLE);

        // URL to get the file media content
        String videoUrl = "https://www.googleapis.com/drive/v3/files/" + fileId + "?alt=media";
        Uri uri = Uri.parse(videoUrl);

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + accessToken);

        mediaController = new MediaController(this);
        mediaController.setAnchorView(videoView);
        videoView.setMediaController(mediaController);

        videoView.setVideoURI(uri, headers);

        videoView.setOnPreparedListener(mp -> {
            preparedVideoWidth = mp.getVideoWidth();
            preparedVideoHeight = mp.getVideoHeight();
            Log.i(TAG, "Video prepared. Dimensions: " + preparedVideoWidth + "x" + preparedVideoHeight);

            progressBar.setVisibility(View.GONE);
            adjustVideoLayout(preparedVideoWidth, preparedVideoHeight);
            videoView.start();
        });

        videoView.setOnErrorListener((mp, what, extra) -> {
            Log.e(TAG, "VideoView Error: what=" + what + " extra=" + extra);
            progressBar.setVisibility(View.GONE);
            Toast.makeText(this, R.string.failed_play_video,
                    Toast.LENGTH_LONG).show();
            return false;
        });

        videoView.setOnCompletionListener(mp -> {
            // Optional: finish or show replay UI
        });
    }

    private void adjustVideoLayout(int videoWidth, int videoHeight) {
        if (videoWidth <= 0 || videoHeight <= 0) {
            return;
        }
        float videoAspect = (float) videoWidth / videoHeight;
        View container = findViewById(R.id.main);
        int containerWidth = container.getWidth();
        int containerHeight = container.getHeight();

        if (containerWidth <= 0 || containerHeight <= 0) {
            containerWidth = getResources().getDisplayMetrics().widthPixels;
            containerHeight = getResources().getDisplayMetrics().heightPixels;
        }

        if (containerWidth <= 0 || containerHeight <= 0) {
            return;
        }

        float containerAspect = (float) containerWidth / containerHeight;
        ViewGroup.LayoutParams lp = videoView.getLayoutParams();
        if (videoAspect > containerAspect) {
            lp.width = containerWidth;
            lp.height = (int) (containerWidth / videoAspect);
        } else {
            lp.height = containerHeight;
            lp.width = (int) (containerHeight * videoAspect);
        }
        Log.i(TAG, "Adjusting VideoView layout: " + lp.width + "x" + lp.height);
        videoView.setLayoutParams(lp);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (preparedVideoWidth > 0 && preparedVideoHeight > 0 && videoView != null) {
            videoView.post(() -> adjustVideoLayout(preparedVideoWidth, preparedVideoHeight));
        }
    }

    @Override
    protected void onDestroy() {
        if (mediaController != null) {
            mediaController.hide();
            mediaController = null;
        }
        if (videoView != null) {
            videoView.stopPlayback();
            videoView.setMediaController(null);
        }
        super.onDestroy();
    }
}
