package net.ark3us.saferec.net;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import net.ark3us.saferec.R;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampResponse;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

public class FreeTSAClient {
    private static final String TAG = FreeTSAClient.class.getSimpleName();
    private static final String TSA_URL = "https://timestamp.sectigo.com";
    // SHA-256 OID: 2.16.840.1.101.3.4.2.1
    private static final String SHA256_OID = "2.16.840.1.101.3.4.2.1";

    /** Returns the SHA-256 of the file, or null if it cannot be read. */
    public static byte[] sha256(File dataFile) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream fis = new FileInputStream(dataFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = fis.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return digest.digest();
        } catch (Exception e) {
            Log.e(TAG, "Failed to hash file: " + dataFile.getName(), e);
            return null;
        }
    }

    /**
     * Requests an RFC 3161 timestamp for a SHA-256 hash and saves the reply to outputFile.
     * Only the hash is needed, so this can run after the data file is gone.
     */
    public static boolean timestampHash(Context context, byte[] hash, File outputFile) {
        try {
            // 1. Generate TSQ
            TimeStampRequestGenerator tsqGenerator = new TimeStampRequestGenerator();
            tsqGenerator.setCertReq(true);
            TimeStampRequest request = tsqGenerator.generate(new ASN1ObjectIdentifier(SHA256_OID), hash);
            byte[] requestBytes = request.getEncoded();

            // 2. Send via HTTP
            URL url = new URL(TSA_URL);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoOutput(true);
            connection.setDoInput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/timestamp-query");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(requestBytes);
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "TSA responded with code " + responseCode);
                showToast(context, context.getString(R.string.tsa_failed_code, responseCode));
                return false;
            }

            String contentType = connection.getContentType();
            if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/timestamp-reply")) {
                Log.e(TAG, "Invalid content type from TSA: " + contentType);
                showToast(context, context.getString(R.string.tsa_invalid_response));
                return false;
            }

            // 3. Read response
            byte[] responseBytes;
            try (InputStream is = connection.getInputStream()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, read);
                }
                responseBytes = baos.toByteArray();
            }
            Log.i(TAG, "TSA responded with " + responseBytes.length + " bytes");

            if (responseBytes.length == 0) {
                Log.e(TAG, "TSA response is empty");
                showToast(context, context.getString(R.string.tsa_empty_response));
                return false;
            }

            // 4. Validate response
            try {
                TimeStampResponse tsResponse = new TimeStampResponse(responseBytes);
                tsResponse.validate(request);
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse/validate TSA response: " + e.getMessage(), e);
                showToast(context, context.getString(R.string.tsa_validation_failed));
                return false;
            }

            // 5. Save only a valid reply, atomically: outputFile lands in the upload outbox,
            // which is scanned at any time and must never hold a partial or invalid file
            File tmp = new File(context.getCacheDir(), outputFile.getName() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(responseBytes);
            }
            if (!tmp.renameTo(outputFile)) {
                Log.e(TAG, "Failed to move timestamp to " + outputFile.getAbsolutePath());
                tmp.delete();
                return false;
            }

            Log.i(TAG, "Successfully timestamped: " + outputFile.getName());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to timestamp file", e);
            showToast(context, context.getString(R.string.tsa_unreachable));
            return false;
        }
    }

    private static void showToast(Context context, String message) {
        if (context != null) {
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            );
        }
    }
}
