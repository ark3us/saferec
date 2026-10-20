package net.ark3us.saferec.ui;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Sessions just sent to the trash. The trashing is asynchronous and Drive's search still
 * returns a trashed file for some seconds, so the list hides them for a while. Pure Java.
 */
public final class TrashedSessions {
    static final long HIDE_MS = 2 * 60 * 1000L;

    private static final Map<String, Long> hiddenUntil = new HashMap<>();

    private TrashedSessions() {
    }

    public static synchronized void hide(String sessionId, String dataType, long nowMillis) {
        hiddenUntil.put(key(sessionId, dataType), nowMillis + HIDE_MS);
    }

    public static synchronized boolean isHidden(String sessionId, String dataType, long nowMillis) {
        for (Iterator<Long> it = hiddenUntil.values().iterator(); it.hasNext(); ) {
            if (it.next() <= nowMillis) {
                it.remove();
            }
        }
        return hiddenUntil.containsKey(key(sessionId, dataType));
    }

    static synchronized void clear() {
        hiddenUntil.clear();
    }

    private static String key(String sessionId, String dataType) {
        return sessionId + "|" + dataType;
    }
}
