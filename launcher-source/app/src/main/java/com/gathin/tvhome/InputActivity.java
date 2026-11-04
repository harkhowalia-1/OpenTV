package com.gathin.tvhome;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.view.View;

/**
 * Inputs: switches the TV to one of its HDMI (or other) inputs, on TVs that share their inputs with apps
 * (Android TV and Google TV sets). A plug-in streamer like a Chromecast can't change the TV's input itself, so
 * there it says to use the TV's own Input button.
 */
public class InputActivity extends ChoiceScreen {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        build(getString(R.string.inputs), getString(R.string.inputs_sub));
        View first = null;
        TvInputManager tim = getSystemService(TvInputManager.class);
        if (tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (!info.isPassthroughInput() || info.isHidden(this)) continue;
                CharSequence label = info.loadCustomLabel(this);
                if (label == null) label = info.loadLabel(this);
                Drawable icon = info.loadIcon(this);
                String id = info.getId();
                View v = card(icon, 0xFF1C1C21, String.valueOf(label), false, () -> switchTo(id));
                if (first == null) first = v;
            }
        }
        if (first == null) {
            note(getString(R.string.inputs_none));
        } else {
            first.requestFocus();
        }
    }

    private void switchTo(String inputId) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            note(getString(R.string.inputs_failed));
        }
    }
}
