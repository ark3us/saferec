package net.ark3us.saferec.media;

import static net.ark3us.saferec.media.VideoOrientation.UNKNOWN;
import static net.ark3us.saferec.media.VideoOrientation.fromDisplayRotation;
import static net.ark3us.saferec.media.VideoOrientation.recordingRotation;
import static net.ark3us.saferec.media.VideoOrientation.snap;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VideoOrientationTest {

    @Test
    public void firstReadingSnapsToNearestQuadrant() {
        assertEquals(0, snap(10, UNKNOWN));
        assertEquals(90, snap(80, UNKNOWN));
        assertEquals(180, snap(200, UNKNOWN));
        assertEquals(270, snap(300, UNKNOWN));
        assertEquals(0, snap(350, UNKNOWN));
    }

    @Test
    public void flatPhoneKeepsTheCurrentOrientation() {
        assertEquals(90, snap(-1, 90));
        assertEquals(UNKNOWN, snap(-1, UNKNOWN));
    }

    @Test
    public void tiltNearTheBoundaryDoesNotFlip() {
        assertEquals(0, snap(50, 0));   // past 45° but within the hysteresis
        assertEquals(0, snap(59, 0));
        assertEquals(90, snap(60, 0));
        assertEquals(90, snap(40, 90)); // coming back needs the same margin
        assertEquals(0, snap(30, 90));
    }

    @Test
    public void hysteresisWorksAcrossZero() {
        assertEquals(0, snap(310, 0));
        assertEquals(270, snap(300, 0));
        assertEquals(270, snap(320, 270));
        assertEquals(0, snap(330, 270));
    }

    @Test
    public void backCameraAddsTheDeviceRotation() {
        assertEquals(90, recordingRotation(90, 0, false));
        assertEquals(180, recordingRotation(90, 90, false));
        assertEquals(270, recordingRotation(90, 180, false));
        assertEquals(0, recordingRotation(90, 270, false));
    }

    @Test
    public void frontCameraSubtractsTheDeviceRotation() {
        assertEquals(270, recordingRotation(270, 0, true));
        assertEquals(180, recordingRotation(270, 90, true));
        assertEquals(90, recordingRotation(270, 180, true));
        assertEquals(0, recordingRotation(270, 270, true));
    }

    @Test
    public void displayRotationTurnsTheOtherWay() {
        assertEquals(0, fromDisplayRotation(0));
        assertEquals(270, fromDisplayRotation(90));
        assertEquals(180, fromDisplayRotation(180));
        assertEquals(90, fromDisplayRotation(270));
        // Back camera: same result as the old display-based formula (sensor - display)
        for (int display = 0; display < 360; display += 90) {
            assertEquals((90 - display + 360) % 360, recordingRotation(90, fromDisplayRotation(display), false));
        }
    }
}
