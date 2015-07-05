package nz.net.io.jarvis;

import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.support.v4.app.ActionBarDrawerToggle;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBarActivity;
import android.text.Html;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.Animation.AnimationListener;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.gcm.GoogleCloudMessaging;
import com.google.android.gms.iid.InstanceID;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Text;

import java.io.IOException;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import nz.net.io.jarvis.ApiHelper.ApiException;
import nz.net.io.jarvis.ApiHelper.ParseException;

/**
 * Base Jarvis activity including code for making API lookups
 */
public class BaseActivity extends ActionBarActivity implements AnimationListener {

    /**
     * Various view objects
     */
    protected View mTitleBar;
    protected TextView mTitle;
    protected ProgressBar mProgress;
    protected ListView mListView;
    protected TextView mMessageView;
    protected EditText mApiView;

    /**
     * Animation for mTitleBar
     */
    protected Animation mSlideIn;
    protected Animation mSlideOut;

    /**
     * Current response's call data
     */
    protected String function;
    protected String action;
    protected String data;

    /**
     * Current response's response (parsed JSON)
     */
    protected Spanned[] dataArray;
    protected String[] urlArray;
    protected JSONObject[] actionArray;
    protected JSONArray globalActionArray;

    /**
     * Displayed response's title - displayed in mTitle
     */
    protected String mEntryTitle;

    /** TODO WHAT IS THIS? **/
    public static final int ADD_ID = 0x09;

    /**
     * History stack of previous pages browsed in this session. This is
     * referenced when the user taps the "back" key, to possibly intercept and
     * show the last-visited entry, instead of closing the activity.
     */
    protected Stack<String> mHistory = new Stack<String>();

    /**
     * Keep track of last time user tapped "back" hard key. When pressed more
     * than once within {@link #BACK_THRESHOLD}, we treat let the back key fall
     * through and close the app.
     */
    protected long mLastPress = -1;
    protected static final long BACK_THRESHOLD = DateUtils.SECOND_IN_MILLIS / 2;

    public String[] mDrawerTitles;
    public String[] mDrawerActions;
    private DrawerLayout mDrawerLayout;
    private ListView mDrawerList;
    private ActionBarDrawerToggle mDrawerToggle;

    // Track highest dynamic menu option ID (so we know what to remove)
    private Integer lastOptionsMenuId = 0;

    /**
     * {@inheritDoc}
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        // Load preferences (might need to fix this, will be run each API call this way :()
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        ApiHelper.API_ROOT = sharedPref.getString("pref_serverurl", "");
        ApiHelper.API_SECRET = sharedPref.getString("pref_serversecret", "");

        if (!sharedPref.getBoolean("gcm", false)) {
            startService(new Intent(this, RegistrationIntentService.class));
        }

        // Set view
        setContentView(R.layout.lookup);

        // Setup drawer
        // TODO load these from the server
        mDrawerTitles  = new String[] {"Home", "Server", "List", "Logs", "Log Add"};
        mDrawerActions = new String[] {"server connect", "server default", "list default", "log view", "log add %Log_entry"};
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
                );

        // Set the drawer toggle as the DrawerListener
        mDrawerLayout.setDrawerListener(mDrawerToggle);

        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setHomeButtonEnabled(true);


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
        ApiHelper.prepareUserAgent(this);

        // Handle incoming intents
        Intent intent = getIntent();
        onNewIntent(intent);
    }


    /**
     * Because we're singleTop, we handle our own new intents. These usually
     * come from the {@link SearchManager} when a search is requested, or from
     * internal links the user clicks on.
     */
    @Override
    public void onNewIntent(Intent intent) {
        final String action = intent.getAction();
        final String type = intent.getType();

        if (Intent.ACTION_SEARCH.equals(action)) {
            // Start query for incoming search request
            String query = intent.getStringExtra(SearchManager.QUERY);
            Log.d("Jarvis", "Incoming search for: "+query);
            startNavigating(query);
            return;

        } else if (Intent.ACTION_VIEW.equals(action)) {
            // Treat as internal link only if valid Uri and host matches
            Uri data = intent.getData();
            if (data != null && ApiHelper.API_ROOT
                    .equals(data.getHost())) {
                String query = data.getPathSegments().get(0);
                startNavigating(query);
                return;
            }

        } else if (Intent.ACTION_SEND.equals(action) && type != null) {
            if ("text/plain".equals(type)) {
                String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
                if (sharedText != null) {
                    // Update UI to reflect text being shared
                    startNavigating(String.format("list add #tosort %s", sharedText));
                    return;
                }
            }
        }

        // If not recognised, navigate to default screen
        startNavigating(null);
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
            startNavigating(lastEntry);

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
     * Start request in background, which updates the GUI when finished.
     *
     * @param request String describing request to execute.
     */
    void startNavigating(String request) {
        Log.i("Jarvis", String.format("Start navigating: %s", request));

        // If request includes the special character %, turn into an API dialog
        if (request != null && request.contains("%")) {
            openAPI(request);
            return;
        }

        // Start request in background
        new LookupTask().execute(request);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.lookup, menu);
        return true;
    }

    /**
     * Show an about dialog, which includes a link to the APK (for updates)
     */
    protected void showAbout() {
        // Inflate the about view
        View messageView = getLayoutInflater().inflate(R.layout.about, null, false);

        // Get preferences required
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        String apkurl = sharedPref.getString("pref_apkurl", "");
        String descrip = this.getString(R.string.app_descrip);
        descrip = descrip.replace("$URL", apkurl);

        // Create text and make link clickable
        TextView textView = (TextView) messageView.findViewById(R.id.about_description);
        textView.setText(Html.fromHtml(descrip));
        textView.setMovementMethod(LinkMovementMethod.getInstance());

        // Get version number from manifest
        Context context = getApplicationContext();
        String versionName;
        try {
            versionName = context.getPackageManager().getPackageInfo(context.getPackageName(), 0 ).versionName;
        } catch (NameNotFoundException e) {
            versionName = "unknown version";
        }

        // Create and show the dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setIcon(R.drawable.app_icon);
        builder.setTitle(String.format("%s v%s", this.getString(R.string.app_name), versionName));
        builder.setView(messageView);
        builder.create();
        builder.show();
    }

    /**
     * Set the title (normally the request string).
     */
    protected void setEntryTitle(String entryText) {
        if (mTitle != null) {
            mEntryTitle = entryText;
            mTitle.setText(mEntryTitle);
        }
    }

    /**
     * Render the response content in the activity.
     * 
     * This means parsing the JSON response
     */
    protected void setEntryContent(String entryContent) {

        // Store the parsed JSON data
        JSONObject json;
        dataArray = null;           // This is each response line
        urlArray = null;            // This is the request string for each response line (if clickable)
        actionArray = null;         // These are the actions for each response line
        globalActionArray = null;   // These are the global actions specified in response

        // Response title message
        String message = "";

        /**
         * Flag determining if the request made changes, in which case it can't be re-run
         * e.g. by being in the history stack
         */
        Integer write = 0;

        // Add result to debug log
        Log.d("Jarvis", entryContent);

        // Check we got some form of content
        if (entryContent != null) {

            // Catch any exceptions
            try {
                json = new JSONObject(entryContent);

                // Drill into the JSON response to find the content body
                Integer state = json.getInt("state");
                message = json.getString("message");

                // If state != success, append to message
                if (state != 1) {
                    message = message + String.format(" (STATE: %s)", state);
                }

                // Display notification
                if (!json.isNull("notification")) {
                    String notification = json.getString("notification");
                    if (!TextUtils.isEmpty(notification)) {
                        Context context = getApplicationContext();
                        int duration = Toast.LENGTH_LONG;

                        Toast toast = Toast.makeText(context, notification, duration);
                        toast.show();
                    }
                }

                // Check if a redirect, and if it is update the api call in title
                if (!json.isNull("redirected")) {
                    setEntryTitle(json.getString("redirected"));
                }

                if (!json.isNull("write")) {
                    write = json.getInt("write");
                }

                if (!json.isNull("actions")) {
                    globalActionArray = json.getJSONArray("actions");
                }

                if (!json.isNull("data")) {
                    JSONArray data = json.getJSONArray("data");

                    // Setup arrays
                    dataArray = new Spanned[data.length()];
                    urlArray = new String[data.length()];
                    actionArray = new JSONObject[data.length()];

                    if (data.length() > 0) {

                        // Current position
                        Integer i = 0;
                        while (i < data.length()) {

                            // Item and contents
                            JSONArray item = data.optJSONArray(i);
                            if (item != null) {

                                // Get text (as HTML)
                                dataArray[i] = android.text.Html.fromHtml(item.getString(0));

                                // Get request string (if clickable)
                                if (item.length() > 1) {
                                    urlArray[i]  = item.getString(1);
                                }

                                // Get actions (if any)
                                if (item.length() > 2) {
                                    JSONObject actions = item.optJSONObject(2);
                                    if (actions != null) {
                                        actionArray[i] = actions;
                                    }
                                }
                            }

                            i++;
                        }
                    }
                }

            } catch (JSONException e) {
                // Exception occurred when parsing response
                Log.e("Jarvis", "Errors: "+e);
                message = "<br>Unparseable:<br>" + entryContent;
            }
        } else {
            // Response is NULL
            message += "<h3 style=\"margin-bottom: 2px; padding: 0;\">ERROR</h3>";
            message += "<p>Server unavailable</p>";
        }

        // Push any current URL onto the history stack if it's not a write action
        if (write == 0 && !TextUtils.isEmpty(mEntryTitle)) {
            // TODO handle redirects
            // Also check to make sure we are not adding two duplicate entries in a row
            if (mHistory.size() == 0 || !mHistory.lastElement().equals(mEntryTitle)) {
                mHistory.add(mEntryTitle);
            }
        }

        mMessageView.setText(message);

        // No data. Hide list view
        if (dataArray == null || dataArray.length == 0) {
            Log.v("Jarvis", "No data items");
            mListView.setVisibility(View.GONE);
            return;
        }

        // Display list view, adapted to show a dataArray
        mListView.setVisibility(View.VISIBLE);
        ArrayAdapter<Spanned> adapter = new ArrayAdapter<Spanned>(this,
                R.layout.list_item, dataArray);
        mListView.setAdapter(adapter);

        // Handle clicks to list items
        // If they have an associated action, do that
        OnItemClickListener mItemClickedHandler = new OnItemClickListener() {
            public void onItemClick(AdapterView parent, View v, int position, long id) {
                String actionstring = urlArray[position];
                if (actionstring == "null" || actionstring == null) {
                    return;
                }

                // If it has an action, do it
                startNavigating(actionstring);
            }
        };
        mListView.setOnItemClickListener(mItemClickedHandler);

        // Handle long-clicks for items (see onCreateContextMenu below)
        registerForContextMenu(mListView);
    }

    /**
     * Display context menu when long clicking items
     */
    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        // Only handle list view items
        if (v.getId() == R.id.listview) {
            AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) menuInfo;

            // If this item has an associated action array
            if (actionArray != null && actionArray.length > info.position && actionArray[info.position] != null) {
                // Set title of the context menu to the list item
                menu.setHeaderTitle(dataArray[info.position]);

                // Loop through actions adding to the menu
                for (int i = 0; i < actionArray[info.position].names().length(); i++) {
                    menu.add(Menu.NONE, i, i, actionArray[info.position].names().optString(i));
                }
            }
        }
    }

    /**
     * Handle clicking of menu items in the context menu
     */
    @Override
    public boolean onContextItemSelected(MenuItem item) {
        // Get context menu info so we can original list item clicked (item.position)
        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo)item.getMenuInfo();

        // Get menu item clicked
        int menuItemIndex = item.getItemId();

        // Get menu items value (action string), by using the menuitemindex as an array key
        String listItemAction = actionArray[info.position].optString(actionArray[info.position].names().optString(menuItemIndex));

        // Navigate there
        startNavigating(listItemAction);
        return true;
    }

    /**
     * Track, add and remove all dynamic options
     */
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        // Start ID's at 1000 (other IDs are much, much higher)
        Integer startId = 1000;
        Integer i = startId;

        // We need to remove all previous items
        while (i <= lastOptionsMenuId) {
            Log.d("Jarvis", "Removed options menu item " + i);
            menu.removeItem(i);
            i++;
        }

        // Check if any dynamic options specified for this response
        if (globalActionArray != null && globalActionArray.length() > 0) {
            // Add all to the menu
            i = 0;
            while (i < globalActionArray.length()) {
                JSONArray item = globalActionArray.optJSONArray(i);
                try {
                    menu.add(0, startId + i, 0, item.getString(0));
                } catch (JSONException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
                Log.d("Jarvis", "Added options menu item " + (startId + i));
                i++;
            }

            // Record last ID
            lastOptionsMenuId = startId + i - 1; // (to counter last i++)
            Log.d("Jarvis", "Last options menu item added " + (startId + i));
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

        Integer returnId = item.getItemId();

        switch (returnId) {

        case R.id.lookup_apicall:
            openAPI("");
            return true;

        case R.id.lookup_help:
            openHelp();
            return true;

        case R.id.lookup_about:
            showAbout();
            return true;

        case R.id.lookup_preferences:
            startActivity(new Intent(this, SettingsActivity.class));
            return true;

        default:
            // Check if any global actions available. If so, check if this is one of them
            // being selected
            if (globalActionArray != null && globalActionArray.length() > 0) {
                if (returnId >= 1000 && returnId <= lastOptionsMenuId) {
                    Integer menuId = returnId - 1000;
                    if (menuId < globalActionArray.length()) {
                        JSONArray actionItem = globalActionArray.optJSONArray(menuId);

                        try {
                            startNavigating(actionItem.getString(1));
                        } catch (JSONException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        }

                        return true;
                    }
                }
            }
        }
        return false;
    }

    public void openAPI(String defaultText) {
        AlertDialog.Builder alert = new AlertDialog.Builder(this);
        String title = "API call";

        // If this is a dynamic call, change the header to match
        // the request variable, and remove the var from the string
        if (defaultText != null && defaultText.contains("%")) {
            // Find dynamic part
            Pattern pattern = Pattern.compile("(%[A-Za-z0-9_]+)");
            Matcher matcher = pattern.matcher(defaultText);
            if (matcher.find())
            {
                // Change title and clean up
                title = matcher.group(1);
                title = title.replace("%", "");
                title = title.replace("_", " ");

                // Remove from string
                defaultText = defaultText.replace(matcher.group(1), "");

                // Remove default text wrapper
                defaultText = defaultText.replace("{{", "");
                defaultText = defaultText.replace("}}", "");
            }
        }

        alert.setTitle(title);

        // Set an EditText view to get user input
        mApiView = new EditText(this);
        mApiView.setText(defaultText);
        mApiView.setSelection(mApiView.getText().length());
        alert.setView(mApiView);

        mApiView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    mApiView.post(mShowImeRunnable);
                } else {
                    mApiView.removeCallbacks(mShowImeRunnable);
                    InputMethodManager imm = (InputMethodManager) mApiView.getContext()
                            .getSystemService(Context.INPUT_METHOD_SERVICE);

                    if (imm != null) {
                        imm.hideSoftInputFromWindow(mApiView.getWindowToken(), 0);
                    }
                }
            }
        });

        alert.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {
                String value = mApiView.getText().toString();
                startNavigating(value);
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
        startNavigating(func + " help");
    }

    private void openLookup(String query) {
        Intent i = new Intent(
                BaseActivity.this,
                BaseActivity.class
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


    /**
     * Make the {@link ProgressBar} visible when our in-animation finishes.
     */
    public void onAnimationEnd(Animation animation) {
        mProgress.setVisibility(View.VISIBLE);
    }

    public void onAnimationRepeat(Animation animation) {
        // Not interested if the animation repeats
    }

    public void onAnimationStart(Animation animation) {
        // Not interested when the animation starts
    }

    /**
     * Background task to handle API requests. This correctly shows and
     * hides the loading animation from the GUI thread before starting a
     * background query to the Jarvis server. When finished, it transitions
     * back to the GUI thread where it updates with the newly-found entry.
     */
    protected class LookupTask extends AsyncTask<String, String, String> {
        private static final String TAG = "LookupTask";

        /**
         * Before jumping into background thread, start sliding in the
         * {@link ProgressBar}. We'll only show it once the animation finishes.
         */
        @Override
        protected void onPreExecute() {
            if (mTitleBar != null) {
                mTitleBar.startAnimation(mSlideIn);
            }
        }

        /**
         * Perform the background query using {@link ApiHelper}, which
         * may return an error message as the result.
         */
        @Override
        protected String doInBackground(String... args) {
            String call = args[0];
            String response = null;

            try {
                // Default query
                if (call == null) {
                    call = "server connect";
                }

                // Split query into function, action and data
                String[] split = call.split(" ", 2);
                if (split.length > 0) {
                    function = split[0];
                }
                if (split.length > 1) {
                    action = split[1];
                }
                if (split.length > 2) {
                    data = split[2];
                }

                // Update mTitle with the query
                publishProgress(call);

                // Load response and return
                response = ApiHelper.getPageContent(call);

                // TODO handle redirect with publishProgress
            } catch (ApiException e) {
                Log.e(TAG, "Problem making request - API", e);
            } catch (ParseException e) {
                Log.e(TAG, "Problem making request - parse issue", e);
            }

            return response;
        }

        /**
         * Our progress update pushes a title bar update.
         */
        @Override
        protected void onProgressUpdate(String... args) {
            String call = args[0];
            setEntryTitle(call);
        }

        /**
         * When finished, push the newly-found response into our
         * view and hide the {@link ProgressBar}.
         */
        @Override
        protected void onPostExecute(String parsedText) {
            if (mTitleBar != null) {
                mTitleBar.startAnimation(mSlideOut);
                mProgress.setVisibility(View.INVISIBLE);
            }

            setEntryContent(parsedText);
        }
    }

    /**
     * This is used in openAPI to show the softkeyboard for the TextView.
     */
    private Runnable mShowImeRunnable = new Runnable() {
        public void run() {
            InputMethodManager imm = (InputMethodManager) mApiView.getContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE);

            if (imm != null) {
                imm.showSoftInput(mApiView, 0);
            }
        }
    };
}