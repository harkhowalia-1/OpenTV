package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

/**
 * Android's answer to an update install: asks the user to confirm when Android wants that, and passes the
 * outcome on to the update screen.
 */
public class UpdateReceiver extends BroadcastReceiver {

    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context ctx, Intent intent) {
        if (!Updater.ACTION_STATUS.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String pkg = intent.getStringExtra("package");
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    ctx.startActivity(confirm);
                    return;
                } catch (ActivityNotFoundException | SecurityException e) {
                    status = PackageInstaller.STATUS_FAILURE;
                }
            }
        }
        UpdateActivity.installed(pkg, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
    }
}
