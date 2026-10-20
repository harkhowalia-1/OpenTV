package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;

import java.util.List;

/**
 * Exists only so the launcher may read the TV's media sessions for Now Playing (Android ties that access to
 * a notification listener, enabled once over adb). It does nothing with notifications.
 */
public class MediaListener extends NotificationListenerService {

    /** The session to show: one that is playing, else the most recent one that has something loaded. */
    static MediaController current(Context ctx) {
        try {
            MediaSessionManager msm = ctx.getSystemService(MediaSessionManager.class);
            List<MediaController> all = msm.getActiveSessions(new ComponentName(ctx, MediaListener.class));
            MediaController fallback = null;
            for (MediaController c : all) {
                PlaybackState s = c.getPlaybackState();
                if (s == null || c.getMetadata() == null) continue;
                if (s.getState() == PlaybackState.STATE_PLAYING) return c;
                if (fallback == null && s.getState() != PlaybackState.STATE_NONE && s.getState() != PlaybackState.STATE_ERROR) {
                    fallback = c;
                }
            }
            return fallback;
        } catch (SecurityException | NullPointerException e) {
            return null;
        }
    }
}
