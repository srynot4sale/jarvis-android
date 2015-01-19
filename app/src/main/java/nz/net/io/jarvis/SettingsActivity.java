package nz.net.io.jarvis;

import android.app.Activity;
import android.os.Bundle;

/**
 * This is for displaying the settings (or preferences) activity
 * @author Aaron Barnes
 */
public class SettingsActivity extends Activity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getFragmentManager().beginTransaction().replace(android.R.id.content,
                new SettingsFragment()).commit();
    }
}