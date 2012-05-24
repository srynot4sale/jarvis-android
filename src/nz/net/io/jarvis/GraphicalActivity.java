/*
 * Copyright (C) 2012 Aaron Barnes <aaron.barnes@hbcosmo.com>
 *

 */

package nz.net.io.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Stack;


/**
 * Activity that lets users browse through Wiktionary content. This is just the
 * user interface, and all API communication and parsing is handled in
 * {@link ExtendedWikiHelper}.
 */
public class GraphicalActivity extends Activity {
    private static final String TAG = "GraphicalActivity";

    /**
     * History stack of previous words browsed in this session. This is
     * referenced when the user taps the "back" key, to possibly intercept and
     * show the last-visited entry, instead of closing the activity.
     */
    private Stack<String> mHistory = new Stack<String>();

    private String mEntryTitle;

    /**
     * Keep track of last time user tapped "back" hard key. When pressed more
     * than once within {@link #BACK_THRESHOLD}, we treat let the back key fall
     * through and close the app.
     */
    private long mLastPress = -1;

    private static final long BACK_THRESHOLD = DateUtils.SECOND_IN_MILLIS / 2;

    /**
     * {@inheritDoc}
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.graphical);

        GridView gridview = (GridView) findViewById(R.id.gridview);
        gridview.setAdapter(new MenuAdapter(this));

        // Prepare User-Agent string for wiki actions
        ExtendedWikiHelper.prepareUserAgent(this);

        // Handle incoming intents as possible searches or links
        //onNewIntent(getIntent());
    }

    /**
     * Intercept the back-key to try walking backwards along our word history
     * stack. If we don't have any remaining history, the key behaves normally
     * and closes this activity.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Handle back key as long we have a history stack
/*        if (keyCode == KeyEvent.KEYCODE_BACK && !mHistory.empty()) {

            // Compare against last pressed time, and if user hit multiple times
            // in quick succession, we should consider bailing out early.
            long currentPress = SystemClock.uptimeMillis();
            if (currentPress - mLastPress < BACK_THRESHOLD) {
                return super.onKeyDown(keyCode, event);
            }
            mLastPress = currentPress;

            // Pop last entry off stack and start loading
            String lastEntry = mHistory.pop();
            startNavigating(lastEntry, false);

            return true;
        }
*/

        // Otherwise fall through to parent
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // On Enter keypress, open API call dialog
        if (event.getKeyCode() == KeyEvent.KEYCODE_ENTER)
        {
            onSearchRequested();
            return true;
        }

        return super.dispatchKeyEvent(event);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.lookup, menu);
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.lookup_apicall: {
                onSearchRequested();
                return true;
            }
            case R.id.lookup_about: {
                showAbout();
                return true;
            }
        }
        return false;
    }

    /**
     * Show an about dialog that cites data sources.
     */
    protected void showAbout() {
        // Inflate the about message contents
        View messageView = getLayoutInflater().inflate(R.layout.about, null, false);

        // When linking text, force to always use default color. This works
        // around a pressed color state bug.
        TextView textView = (TextView) messageView.findViewById(R.id.about_credits);
        int defaultColor = textView.getTextColors().getDefaultColor();
        textView.setTextColor(defaultColor);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setIcon(R.drawable.app_icon);
        builder.setTitle(R.string.app_name);
        builder.setView(messageView);
        builder.create();
        builder.show();
    }

    public class MenuAdapter extends BaseAdapter {
        private Context mContext;

        public MenuAdapter(Context c) {
            mContext = c;
        }

        public int getCount() {
            return mFunctions.length;
        }

        public Object getItem(int position) {
            return null;
        }

        public long getItemId(int position) {
            return 0;
        }

        // create a new ImageView for each item referenced by the Adapter
        public View getView(int position, View convertView, ViewGroup parent) {
            Button buttonView;
            if (convertView == null) {  // if it's not recycled, initialize some attributes
                buttonView = new Button(mContext);
                //buttonView.setLayoutParams(new GridView.LayoutParams(85, 85));
                //buttonView.setPadding(8, 8, 8, 8);
            } else {
                buttonView = (Button) convertView;
            }

            buttonView.setText(mFunctions[position]);

            buttonView.setOnClickListener(new Button.OnClickListener() {
                public void onClick(View v) {
                    Button b = (Button)v;
                    String buttonText = b.getText().toString();

                    // Open LookupActivity with functions help page
                    Intent i = new Intent(
                            GraphicalActivity.this,
                            LookupActivity.class
                    );
                    i.setAction(Intent.ACTION_SEARCH);
                    i.putExtra(SearchManager.QUERY, buttonText+" help");

                    startActivity(i);
                }
            });

            return buttonView;
        }

        // references to our images
        private String[] mFunctions = {
                "server",
                "list",
                "calendar",
                "habit"
        };
    }
}
