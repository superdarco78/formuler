package pl.superdarco.paneltv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Panel TV v1 - uruchamia usluge w tle i od razu sie zamyka. */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        startForegroundService(new Intent(this, PanelService.class));
        finish();
    }
}
