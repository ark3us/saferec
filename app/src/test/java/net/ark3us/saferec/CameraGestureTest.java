package net.ark3us.saferec;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CameraGestureTest {
    @Test public void noFixedPackageAllowsOthers() {
        assertTrue(CameraGesture.allowsOtherApps(null, "net.ark3us.saferec"));
        assertTrue(CameraGesture.allowsOtherApps("", "net.ark3us.saferec"));
    }

    @Test public void ourOwnPackageAllows() {
        assertTrue(CameraGesture.allowsOtherApps("net.ark3us.saferec", "net.ark3us.saferec"));
    }

    @Test public void anotherFixedCameraDoesNot() {
        assertFalse(CameraGesture.allowsOtherApps("com.google.android.GoogleCamera", "net.ark3us.saferec"));
    }
}
