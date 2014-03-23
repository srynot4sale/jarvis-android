/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nz.net.io.jarvis;

import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.support.v4.app.ActionBarDrawerToggle;
import android.support.v4.widget.DrawerLayout;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.support.v4.widget.DrawerLayout;


/**
 * Activity that lets users browse Jarvis content. This is just the
 * user interface, and all API communication and parsing is handled in
 * {@link ExtendedWikiHelper}.
 */
public class LookupActivity extends BaseActivity {

    public String[] mDrawerTitles;
    public String[] mDrawerActions;
    private DrawerLayout mDrawerLayout;
    private ListView mDrawerList;
    private ActionBarDrawerToggle mDrawerToggle;
    
    /**
     * {@inheritDoc}
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        // Load preferences (might need to fix this, will be run each API call this way :()
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        SimpleWikiHelper.API_ROOT = sharedPref.getString("pref_serverurl", "");
        SimpleWikiHelper.API_SECRET = sharedPref.getString("pref_serversecret", "");

        setContentView(R.layout.lookup);
        
        mDrawerTitles  = new String[] {"Home", "Server", "List"};
        mDrawerActions = new String[] {"server connect", "server default", "list default"};
        mDrawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);
        mDrawerList = (ListView) findViewById(R.id.left_drawer);

        // Set the adapter for the list view
        mDrawerList.setAdapter(new ArrayAdapter<String>(this,
                R.layout.drawer_list_item, mDrawerTitles));
        // Set the list's click listener
        mDrawerList.setOnItemClickListener(new DrawerItemClickListener());

        mDrawerToggle = new ActionBarDrawerToggle(
                this,                 /* host Activity */
                mDrawerLayout,         /* DrawerLayout object */
                R.drawable.ic_drawer,  /* nav drawer icon to replace 'Up' caret */
                0,
                0
                ) {};

        // Set the drawer toggle as the DrawerListener
        mDrawerLayout.setDrawerListener(mDrawerToggle);

        getActionBar().setDisplayHomeAsUpEnabled(true);
        getActionBar().setHomeButtonEnabled(true);
        

        // Load animations used to show/hide progress bar
        mSlideIn = AnimationUtils.loadAnimation(this, R.anim.slide_in);
        mSlideOut = AnimationUtils.loadAnimation(this, R.anim.slide_out);

        // Listen for the "in" animation so we make the progress bar visible
        // only after the sliding has finished.
        mSlideIn.setAnimationListener(this);

        mTitleBar = findViewById(R.id.title_bar);
        mTitle = (TextView) findViewById(R.id.title);
        mProgress = (ProgressBar) findViewById(R.id.progress);
        mMessageView = (TextView) findViewById(R.id.messageview);
        mListView = (ListView) findViewById(R.id.listview);

        // Prepare User-Agent string for wiki actions
        SimpleWikiHelper.prepareUserAgent(this);

        // Handle incoming intents as possible searches or links
        onNewIntent(getIntent());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.lookup, menu);
        return true;
    }

    /**
     * Intercept the back-key to try walking backwards along our word history
     * stack. If we don't have any remaining history, the key behaves normally
     * and closes this activity.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Handle back key as long we have a history stack
        if (keyCode == KeyEvent.KEYCODE_BACK && mHistory.size() >= 2) {

            // Compare against last pressed time, and if user hit multiple times
            // in quick succession, we should consider bailing out early.
            long currentPress = SystemClock.uptimeMillis();
            if (currentPress - mLastPress < BACK_THRESHOLD) {
                return super.onKeyDown(keyCode, event);
            }
            mLastPress = currentPress;

            // Pop off current entry
            mHistory.pop();
            // Get last entry
            String lastEntry = mHistory.pop();
            startNavigating(lastEntry, false);

            return true;
        }

        // Otherwise fall through to parent
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // On Enter key press, open API call dialog
        if (event.getKeyCode() == KeyEvent.KEYCODE_ENTER)
        {
            onSearchRequested();
            return true;
        }

        return super.dispatchKeyEvent(event);
    }

    /**
     * Because we're singleTop, we handle our own new intents. These usually
     * come from the {@link SearchManager} when a search is requested, or from
     * internal links the user clicks on.
     */
    @Override
    public void onNewIntent(Intent intent) {
        final String action = intent.getAction();
        if (Intent.ACTION_SEARCH.equals(action)) {
            // Start query for incoming search request
            String query = intent.getStringExtra(SearchManager.QUERY);
            startNavigating(query, true);

        } else if (Intent.ACTION_VIEW.equals(action)) {
            // Treat as internal link only if valid Uri and host matches
            Uri data = intent.getData();
            if (data != null && SimpleWikiHelper.API_ROOT
                    .equals(data.getHost())) {
                String query = data.getPathSegments().get(0);
                startNavigating(query, true);
            }

        } else {
            // If not recognised, then start showing random word
            startNavigating(null, true);
        }
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (mEntryTitle != null && mEntryTitle.length() > 9 && mEntryTitle.substring(0, 9).equals("list view")) {
            // try to see if already exists
            MenuItem editItem = menu.findItem(ADD_ID);
            if (editItem == null) {
                menu.add(0, ADD_ID, 0, "Add").setIcon(android.R.drawable.ic_menu_add);
            }
        } else {
            // we need to remove it when the condition fails
            menu.removeItem(ADD_ID);
        }

        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Pass the event to ActionBarDrawerToggle, if it returns
        // true, then it has handled the app icon touch event
        if (mDrawerToggle.onOptionsItemSelected(item)) {
            return true;
        }
        
        switch (item.getItemId()) {
        case R.id.lookup_apicall:
            openAPI("");
            //onSearchRequested();
            return true;
        case R.id.lookup_help:
            openHelp();
            return true;
        case R.id.lookup_about:
            showAbout();
            return true;
        case ADD_ID:
            openAPI("list add " + mEntryTitle.substring(10) + " ");
            return true;
        case R.id.lookup_preferences:
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return false;
    }

    public void openAPI(String defaultText) {
        AlertDialog.Builder alert = new AlertDialog.Builder(this);

        alert.setTitle("API Call");
        //alert.setMessage("Message");

        // Set an EditText view to get user input
        final EditText input = new EditText(this);
        input.setText(defaultText);
        input.setSelection(input.getText().length());
        alert.setView(input);

        alert.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {
                String value = input.getText().toString();
                startNavigating(value, false);
            }
        });

        alert.show();
    }


    public void openHelp() {
        String func;
        if (function != null && function.length() > 0) {
            func = function;
        } else {
            func = "server";
        }
        startNavigating(func + " help", true);
    }

    private void openLookup(String query) {
        Intent i = new Intent(
                LookupActivity.this,
                LookupActivity.class
                );
        i.setAction(Intent.ACTION_SEARCH);
        i.putExtra(SearchManager.QUERY, query);

        startActivity(i);
    }
    
    
    private class DrawerItemClickListener implements ListView.OnItemClickListener {
        public void onItemClick(AdapterView parent, View view, int position, long id) {
            mDrawerList.setItemChecked(position, true);
            mDrawerLayout.closeDrawer(mDrawerList);
        	openLookup(mDrawerActions[position]);
        }
    }
}
