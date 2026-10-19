package net.ark3us.saferec.media;

/**
 * Orientation math for the recorded video, free of Android types so it can be unit tested.
 *
 * Device orientation follows OrientationEventListener: degrees the device is turned clockwise
 * from its natural (portrait) position, so 90 means its left side is up.
 */
public final class VideoOrientation {
    public static final int UNKNOWN = -1;
    // How far past the 45° boundary a tilt must go before the quadrant changes
    static final int HYSTERESIS_DEG = 15;

    private VideoOrientation() {
    }

    /**
     * Snaps a raw device orientation to 0/90/180/270. Near a boundary it keeps the current
     * quadrant, so a phone held at ~45° does not flip back and forth and split the recording
     * into tiny chunks. A flat phone reports no orientation: the current one is kept.
     */
    public static int snap(int degrees, int current) {
        if (degrees < 0) {
            return current;
        }
        int nearest = (degrees + 45) / 90 % 4 * 90;
        if (current == UNKNOWN || nearest == current) {
            return nearest;
        }
        int fromCurrent = Math.abs(((degrees - current) % 360 + 540) % 360 - 180);
        return fromCurrent >= 45 + HYSTERESIS_DEG ? nearest : current;
    }

    /**
     * Rotation the player applies to the raw camera frames to show them upright (the MP4
     * orientation hint). The front camera faces the other way, so the device rotation counts
     * in reverse: using the back camera formula turned front videos upside down in landscape.
     */
    public static int recordingRotation(int sensorOrientation, int deviceOrientation, boolean frontFacing) {
        int device = frontFacing ? -deviceOrientation : deviceOrientation;
        return ((sensorOrientation + device) % 360 + 360) % 360;
    }

    /** Device orientation matching a display rotation (Surface.ROTATION_* times 90). */
    public static int fromDisplayRotation(int displayDegrees) {
        return (360 - displayDegrees) % 360;
    }
}
