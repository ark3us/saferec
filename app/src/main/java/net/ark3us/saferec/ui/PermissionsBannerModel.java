package net.ark3us.saferec.ui;

/**
 * What the permissions banner says, and whether a start asks first. Pure Java: no Android imports.
 * A recording needs the microphone only: in Video mode a refused camera records sound only
 * (SafeRecService), so it never blocks a start.
 */
public final class PermissionsBannerModel {

    public enum Kind {
        HIDDEN,
        /** Audio mode, microphone refused. */
        NEEDS_MICROPHONE,
        /** Video mode, microphone refused. */
        NEEDS_CAMERA_AND_MICROPHONE,
        /** Video mode, microphone granted, camera refused: it records sound only. */
        SOUND_ONLY
    }

    private PermissionsBannerModel() {}

    /**
     * @param audioOnly         the viewfinder's mode is Audio
     * @param microphone        RECORD_AUDIO is granted
     * @param camera            CAMERA is granted
     * @param microphoneRefused the microphone was refused since this screen opened
     * @param cameraRefused     the camera was refused since this screen opened
     */
    public static Kind kindOf(boolean audioOnly, boolean microphone, boolean camera,
                              boolean microphoneRefused, boolean cameraRefused) {
        if (!microphone) {
            if (!microphoneRefused) return Kind.HIDDEN;
            // Only the microphone is missing when the camera is already granted, or not wanted
            return audioOnly || camera ? Kind.NEEDS_MICROPHONE : Kind.NEEDS_CAMERA_AND_MICROPHONE;
        }
        if (!audioOnly && !camera && cameraRefused) return Kind.SOUND_ONLY;
        return Kind.HIDDEN;
    }

    /**
     * Whether a start asks for permissions before it records. Without the microphone nothing
     * records, so it asks in every mode. In Video mode a missing camera is asked for together with
     * it, once per screen: after a refusal the start goes at once with sound only, and the banner's
     * "Allow" asks again. Asking at every tap would put a dialog before each start.
     */
    public static boolean mustAsk(boolean audioOnly, boolean microphone, boolean camera, boolean cameraRefused) {
        if (!microphone) return true;
        return !audioOnly && !camera && !cameraRefused;
    }
}
