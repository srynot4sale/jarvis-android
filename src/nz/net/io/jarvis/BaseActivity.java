package nz.net.io.jarvis;

import nz.net.io.jarvis.SimpleWikiHelper.ApiException;
import nz.net.io.jarvis.SimpleWikiHelper.ParseException;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.AsyncTask;
import android.text.Html;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.Animation.AnimationListener;
import android.webkit.WebView;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.GridView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Stack;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;


/**
 * Base Jarvis activity include code for making API lookups
 */
abstract class BaseActivity extends Activity implements AnimationListener {

    protected GridView mGridView;
    protected View mTitleBar;
    protected TextView mTitle;
    protected ProgressBar mProgress;
    protected ListView mListView;
    protected TextView mMessageView;

    protected Animation mSlideIn;
    protected Animation mSlideOut;

    protected String function;
    protected String action;
    protected String data;

    protected Spanned[] dataArray;
    protected String[] urlArray;
    protected JSONObject[] actionArray;

    public static final int ADD_ID = 0x09;

    /**
     * History stack of previous words browsed in this session. This is
     * referenced when the user taps the "back" key, to possibly intercept and
     * show the last-visited entry, instead of closing the activity.
     */
    protected Stack<String> mHistory = new Stack<String>();

    protected String mEntryTitle;

    /**
     * Keep track of last time user tapped "back" hard key. When pressed more
     * than once within {@link #BACK_THRESHOLD}, we treat let the back key fall
     * through and close the app.
     */
    protected long mLastPress = -1;

    protected static final long BACK_THRESHOLD = DateUtils.SECOND_IN_MILLIS / 2;

    /**
     * Start navigating to the given word, pushing any current word onto the
     * history stack if requested. The navigation happens on a background thread
     * and updates the GUI when finished.
     *
     * @param word The dictionary word to navigate to.
     * @param pushHistory If true, push the current word onto history stack.
     */
    void startNavigating(String word, boolean pushHistory) {
        Log.i("Jarvis", String.format("Start navigating: %s", word));

        if (word == "server connect" || word == null) {
            mGridView.setVisibility(View.VISIBLE);
            mTitleBar.setVisibility(View.GONE);
            mTitle.setVisibility(View.GONE);
            mProgress.setVisibility(View.GONE);
        } else {
            mGridView.setVisibility(View.GONE);
            mTitleBar.setVisibility(View.VISIBLE);
            mTitle.setVisibility(View.VISIBLE);
            mProgress.setVisibility(View.VISIBLE);
        }

        // Start lookup for new word in background
        new LookupTask().execute(word);
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
     * Show an about dialog that cites data sources.
     */
    protected void showAbout() {
        // Inflate the about message contents
        View messageView = getLayoutInflater().inflate(R.layout.about, null, false);

        TextView textView = (TextView) messageView.findViewById(R.id.about_description);
        textView.setText(Html.fromHtml(this.getString(R.string.app_descrip)));
        textView.setMovementMethod(LinkMovementMethod.getInstance());

        Context context = getApplicationContext();
        String versionName;
        try {
            versionName = context.getPackageManager().getPackageInfo(context.getPackageName(), 0 ).versionName;
        } catch (NameNotFoundException e) {
            versionName = "unknown";
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setIcon(R.drawable.app_icon);
        builder.setTitle(String.format("%s v%s", this.getString(R.string.app_name), versionName));
        builder.setView(messageView);
        builder.create();
        builder.show();
    }

    /**
     * Set the title for the current entry.
     */
    protected void setEntryTitle(String entryText) {
        if (mTitle != null) {
            mEntryTitle = entryText;
            mTitle.setText(mEntryTitle);
        }
    }

    /**
     * Set the content for the current entry. This will update our
     * {@link WebView} to show the requested content.
     */
    protected void setEntryContent(String entryContent) {

        dataArray = null;
        urlArray = null;
        actionArray = null;

        JSONObject json;
        String message = "";
        Integer write = 0;

        // Check we got some form of content
        if (entryContent != null) {

            Log.d("Jarvis", entryContent);

            try {
                json = new JSONObject(entryContent);
                // Drill into the JSON response to find the content body
                Integer state = json.getInt("state");
                message = json.getString("message");
                if (state != 1) {
                    message = message + String.format(" (STATE: %s)", state);
                }

                if (!json.isNull("write")) {
                    write = json.getInt("write");
                }

                if (!json.isNull("data")) {
                    JSONArray data = json.getJSONArray("data");

                    // Setup dataArray
                    dataArray = new Spanned[data.length()];
                    urlArray = new String[data.length()];
                    actionArray = new JSONObject[data.length()];

                    if (data.length() > 0) {
                        Integer i = 0;
                        while (i < data.length()) {
                            JSONArray item = data.optJSONArray(i);
                            if (item != null)
                            {
                                dataArray[i] = android.text.Html.fromHtml(item.getString(0));
                                if (item.length() > 1) {
                                    urlArray[i]  = item.getString(1);
                                }
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
                Log.e("Jarvis", "Errors: "+e);
                message = "<br>Unparseable:<br>" + entryContent;
            }
        } else {
            message += "<h3 style=\"margin-bottom: 2px; padding: 0;\">ERROR</h3>";
            message += "<p>Server unavailable</p>";
        }

        // Push any current url onto the history stack if it's not a write action
        if (write == 0 && !TextUtils.isEmpty(mEntryTitle)) {
            // Also check to make sure we are not adding two duplicate entries in a row
            if (mHistory.size() == 0 || !mHistory.lastElement().equals(mEntryTitle)) {
                mHistory.add(mEntryTitle);
            }
        }

        mMessageView.setText(android.text.Html.fromHtml(message));
        Linkify.addLinks(mMessageView, Linkify.ALL);

        if (dataArray == null || dataArray.length == 0) {
            Log.v("Jarvis", "No data items");
            mListView.setVisibility(View.GONE);
            return;
        }

        mListView.setVisibility(View.VISIBLE);

        ArrayAdapter<Spanned> adapter = new ArrayAdapter<Spanned>(this,
                R.layout.list_item, dataArray);

        mListView.setAdapter(adapter);

        // Create a message handling object as an anonymous class.
        OnItemClickListener mMessageClickedHandler = new OnItemClickListener() {
            public void onItemClick(AdapterView parent, View v, int position, long id) {
                String URL = urlArray[position];
                if (URL == "null" || URL == null) {
                    return;
                }

                // Do something in response to the click
                startNavigating(URL, true);
            }
        };

        mListView.setOnItemClickListener(mMessageClickedHandler);
        registerForContextMenu(mListView);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        if (v.getId() == R.id.listview) {
            AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) menuInfo;

            if (actionArray != null && actionArray.length > info.position && actionArray[info.position] != null) {
                menu.setHeaderTitle(dataArray[info.position]);
                for (int i = 0; i < actionArray[info.position].names().length(); i++) {
                    menu.add(Menu.NONE, i, i, actionArray[info.position].names().optString(i));
                }
            }
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo)item.getMenuInfo();
        int menuItemIndex = item.getItemId();
        String listItemValue = actionArray[info.position].optString(actionArray[info.position].names().optString(menuItemIndex));
        startNavigating(listItemValue, true);
        return true;
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
     * Background task to handle Wiktionary lookups. This correctly shows and
     * hides the loading animation from the GUI thread before starting a
     * background query to the Wiktionary API. When finished, it transitions
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
         * Perform the background query using {@link ExtendedWikiHelper}, which
         * may return an error message as the result.
         */
        @Override
        protected String doInBackground(String... args) {
            String call = args[0];
            String result = null;

            try {
                if (call == null) {
                    call = "server connect";
                }

                // Set function/action
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

                // Push our requested word to the title bar
                publishProgress(call);
                result = SimpleWikiHelper.getPageContent(call);
            } catch (ApiException e) {
                Log.e(TAG, "Problem making wiktionary request", e);
            } catch (ParseException e) {
                Log.e(TAG, "Problem making wiktionary request", e);
            }

            return result;
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
         * When finished, push the newly-found entry content into our
         * {@link WebView} and hide the {@link ProgressBar}.
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
}
