package nz.net.io.jarvis;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class JarvisWebViewClient extends WebViewClient {

    private BaseActivity parentActivity;

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        parentActivity.startNavigating(url, false);
        return true;
    }

    public void setActivity(BaseActivity activity) {
        parentActivity = activity;
    }
}