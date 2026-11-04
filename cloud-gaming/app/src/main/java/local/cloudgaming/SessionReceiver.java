package local.cloudgaming;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * An Xbox sign-in made on the iPhone (Otv Remote), handed over by OtvOS: Microsoft's sign-in cookies and the
 * Xbox site's saved session, so this TV is signed in without typing anything.
 */
public class SessionReceiver extends BroadcastReceiver {

    static final String ACTION = "local.cloudgaming.SESSION";
    /** Sent inside this app when a session arrives, so an open sign-in screen can finish. */
    static final String IMPORTED = "local.cloudgaming.SESSION_IMPORTED";

    @Override
    public void onReceive(Context c, Intent i) {
        String json = i.getStringExtra("session");
        if (json == null) return;
        if (i.getBooleanExtra("test", false)) {
            android.util.Log.i("CloudGaming", "test sign-in hand-off received (nothing changed)");
            return;
        }
        android.util.Log.i("CloudGaming", "Xbox sign-in received from OtvOS");
        String tag = Account.importSession(c, json);
        c.sendBroadcast(new Intent(IMPORTED).setPackage(c.getPackageName()).putExtra("gamertag", tag));
    }
}
