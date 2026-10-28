package pl.superdarco.paneltv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Panel TV v1 - start uslugi po wlaczeniu dekodera. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        c.startForegroundService(new Intent(c, PanelService.class));
    }
}
