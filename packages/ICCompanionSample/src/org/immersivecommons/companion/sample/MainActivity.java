package org.immersivecommons.companion.sample;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView t = new TextView(this);
        t.setText("IC Companion Sample\nlogcat -s ICCompanion");
        setContentView(t);
        // the companion lives on the application context so it outlives this activity
        Companion.start(getApplicationContext(), getIntent().getIntExtra("durationSec", 60));
    }
}
