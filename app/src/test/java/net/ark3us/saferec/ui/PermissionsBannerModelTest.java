package net.ark3us.saferec.ui;

import static net.ark3us.saferec.ui.PermissionsBannerModel.Kind.HIDDEN;
import static net.ark3us.saferec.ui.PermissionsBannerModel.Kind.NEEDS_CAMERA_AND_MICROPHONE;
import static net.ark3us.saferec.ui.PermissionsBannerModel.Kind.NEEDS_MICROPHONE;
import static net.ark3us.saferec.ui.PermissionsBannerModel.Kind.SOUND_ONLY;
import static net.ark3us.saferec.ui.PermissionsBannerModel.kindOf;
import static net.ark3us.saferec.ui.PermissionsBannerModel.mustAsk;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Arguments: audioOnly, microphone granted, camera granted, microphone refused, camera refused. */
public class PermissionsBannerModelTest {
    // ---- The banner ----
    @Test public void nothingRefusedShowsNothing() { assertEquals(HIDDEN, kindOf(false, false, false, false, false)); }
    @Test public void allGrantedShowsNothing() { assertEquals(HIDDEN, kindOf(false, true, true, false, false)); }
    @Test public void microphoneRefusedInAudioMode() { assertEquals(NEEDS_MICROPHONE, kindOf(true, false, true, true, false)); }
    @Test public void microphoneRefusedInVideoModeAsBefore() {
        assertEquals(NEEDS_CAMERA_AND_MICROPHONE, kindOf(false, false, false, true, true));
        assertEquals(NEEDS_CAMERA_AND_MICROPHONE, kindOf(false, false, true, true, false));
    }
    @Test public void cameraRefusedInVideoModeRecordsSoundOnly() { assertEquals(SOUND_ONLY, kindOf(false, true, false, false, true)); }
    @Test public void cameraRefusedDoesNotMatterInAudioMode() { assertEquals(HIDDEN, kindOf(true, true, false, false, true)); }
    @Test public void aCameraGrantedSinceHidesTheBanner() { assertEquals(HIDDEN, kindOf(false, true, true, false, true)); }
    @Test public void soundOnlyNeedsTheMicrophone() { assertEquals(HIDDEN, kindOf(false, false, false, false, true)); }

    // ---- The start ----
    @Test public void allGrantedStartsAtOnce() { assertFalse(mustAsk(false, true, true, false)); }
    @Test public void noMicrophoneAsksInEveryMode() {
        assertTrue(mustAsk(true, false, true, false));
        assertTrue(mustAsk(false, false, true, false));
        assertTrue(mustAsk(false, false, false, true));
    }
    @Test public void audioModeNeverAsksForTheCamera() { assertFalse(mustAsk(true, true, false, false)); }
    @Test public void videoModeAsksForAMissingCameraOnce() { assertTrue(mustAsk(false, true, false, false)); }
    @Test public void aCameraRefusedOnThisScreenStartsSoundOnlyAtOnce() { assertFalse(mustAsk(false, true, false, true)); }
}
