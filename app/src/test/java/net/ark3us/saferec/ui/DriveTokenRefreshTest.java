package net.ark3us.saferec.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;

import org.junit.Test;

import java.io.IOException;

public class DriveTokenRefreshTest {
    private static HttpResponseException drive(int status) {
        return new HttpResponseException.Builder(status, "status " + status, new HttpHeaders()).build();
    }

    @Test
    public void onlyA401IsUnauthorized() {
        assertTrue(DriveTokenRefresh.isUnauthorized(drive(401)));
        assertFalse(DriveTokenRefresh.isUnauthorized(drive(403)));
        assertFalse(DriveTokenRefresh.isUnauthorized(drive(500)));
    }

    @Test
    public void aNetworkErrorIsNotAnAuthError() {
        assertFalse(DriveTokenRefresh.isUnauthorized(new IOException("timeout")));
        assertFalse(DriveTokenRefresh.isUnauthorized(null));
        assertEquals(0, DriveTokenRefresh.httpStatus(new java.net.UnknownHostException("www.googleapis.com")));
    }

    @Test
    public void theStatusIsFoundBehindAWrapper() {
        assertTrue(DriveTokenRefresh.isUnauthorized(new RuntimeException(new IOException(drive(401)))));
        assertEquals(404, DriveTokenRefresh.httpStatus(new IllegalStateException(drive(404))));
    }
}
