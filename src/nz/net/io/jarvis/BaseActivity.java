package nz.net.io.jarvis;

import nz.net.io.jarvis.SimpleWikiHelper.ApiException;
import nz.net.io.jarvis.SimpleWikiHelper.ParseException;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.Animation.AnimationListener;
import android.webkit.WebView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Stack;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;


/**
 * Base Jarvis activity include code for making API lookups
 */
abstract class BaseActivity extends Activity implements AnimationListener {

    protected View mTitleBar;
    protected TextView mTitle;
    protected ProgressBar mProgress;
    protected WebView mWebView;

    protected Animation mSlideIn;
    protected Animation mSlideOut;

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
//    private long mLastPress = -1;

//    private static final long BACK_THRESHOLD = DateUtils.SECOND_IN_MILLIS / 2;

    /**
     * Start navigating to the given word, pushing any current word onto the
     * history stack if requested. The navigation happens on a background thread
     * and updates the GUI when finished.
     *
     * @param word The dictionary word to navigate to.
     * @param pushHistory If true, push the current word onto history stack.
     */
    void startNavigating(String word, boolean pushHistory) {
        // Push any current word onto the history stack
        if (!TextUtils.isEmpty(mEntryTitle) && pushHistory) {
            mHistory.add(mEntryTitle);
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

        // Convert content to HTML
        String html = "";

        JSONObject json;
        String message = "";

        // Check we got some form of content
        if (entryContent != null) {
            try {
                json = new JSONObject(entryContent);
                // Drill into the JSON response to find the content body
                Integer state = json.getInt("state");
                message = json.getString("message");
                if (state != 1) {
                    message = message + String.format(" (STATE: %s)", state);
                }

                html = html + String.format("<p>%s</p>", message);

                if (!json.isNull("data")) {
                    try {
                        JSONArray data = json.getJSONArray("data");
                        if (data.length() > 0) {
                            html = html + "<ul>";

                            Integer i = 0;
                            while (i < data.length()) {
                                html = html + String.format("<li>%s</li>", data.getString(i));
                                i++;
                            }

                            html = html + "</ul>";
                        }
                    } catch (JSONException e) {
                        String data = json.getString("data");
                        html = html + String.format("<ul><li>%s</li></ul>", data);
                    }
                }

            } catch (JSONException e) {
                e.printStackTrace();
                html = html + "<br>Unparseable:<br>" + entryContent;
            }
        } else {
            html  = "<h3 style=\"margin-bottom: 2px; padding: 0;\">ERROR</h3>';";
            html += "<p>Server unavailable [<a href=\"server/connect\">retry</a>]</p>";
        }

        mWebView.loadDataWithBaseURL(
                SimpleWikiHelper.API_ROOT+"/",
                html,
                SimpleWikiHelper.MIME_TYPE,
                SimpleWikiHelper.ENCODING,
                null
        );
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
                // If function is null, attempt to connect to server
                if (call == null) {
                    call = "server connect";
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
            String searchWord = args[0];
            if (searchWord.length() > 7 && searchWord.substring(0, 7).equals("http://")) {
                searchWord = searchWord.substring(30).replace('/',' ');
            }
            setEntryTitle(searchWord);
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
