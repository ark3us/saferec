package net.ark3us.saferec.ui;

import java.util.ArrayList;
import java.util.List;

/** Which cards the first run shows. Pure Java. */
public final class FirstRunSteps {
    public enum Step { ABOUT, PERMISSIONS, THREE_PRESSES }

    private FirstRunSteps() {}

    /** ArrayList, not List.of: that needs API 30 and minSdk is 26. */
    public static List<Step> steps(boolean powerGesture, boolean permissionsGranted) {
        List<Step> steps = new ArrayList<>();
        steps.add(Step.ABOUT);
        if (!permissionsGranted) steps.add(Step.PERMISSIONS);
        if (powerGesture) steps.add(Step.THREE_PRESSES);
        return steps;
    }
}
