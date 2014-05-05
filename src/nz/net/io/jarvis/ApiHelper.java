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

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Pattern;

/**
 * Helper methods to simplify requesting and parsing responses from
 * the Jarvis server API. Before making any requests, you should call
 * {@link #prepareUserAgent(Context)} to generate a User-Agent string based on
 * your application package name and version.
 */
public class ApiHelper {
    private static final String TAG = "ApiHelper";

    /**
     * Jarvis URL. Use {@link String#format(String, Object...)} to insert
     * REST data into URI.
     */
    public static String API_ROOT = "";

    /**
     * Jarvis secret authentication
     */
    public static String API_SECRET = "";

    /**
     * Shared buffer used by {@link #getUrlContent(String)} when reading results
     * from an API request.
     */
    private static byte[] sBuffer = new byte[512];

    /**
     * User-agent string to use when making requests. Should be filled using
     * {@link #prepareUserAgent(Context)} before making any other calls.
     */
    private static String sUserAgent = null;

    /**
     * Mime-type to use when showing parsed results in a {@link WebView}.
     */
    public static final String MIME_TYPE = "text/html";

    /**
     * Encoding to use when showing parsed results in a {@link WebView}.
     */
    public static final String ENCODING = "utf-8";

    /**
     * Thrown when there were problems contacting the remote API server, either
     * because of a network error, or the server returned a bad status code.
     */
    public static class ApiException extends Exception {
        private static final long serialVersionUID = 2601373447800779519L;

        public ApiException(String detailMessage, Throwable throwable) {
            super(detailMessage, throwable);
        }

        public ApiException(String detailMessage) {
            super(detailMessage);
        }
    }

    /**
     * Thrown when there were problems parsing the response to an API call,
     * either because the response was empty, or it was malformed.
     */
    public static class ParseException extends Exception {

        private static final long serialVersionUID = -4358358446067693080L;

        public ParseException(String detailMessage, Throwable throwable) {
            super(detailMessage, throwable);
        }
    }

    /**
     * Prepare the internal User-Agent string for use. This requires a
     * {@link Context} to pull the package name and version number for this
     * application.
     */
    public static void prepareUserAgent(Context context) {
        try {
            // Read package name and version number from manifest
            PackageManager manager = context.getPackageManager();
            PackageInfo info = manager.getPackageInfo(context.getPackageName(), 0);
            sUserAgent = String.format(context.getString(R.string.template_user_agent),
                    info.packageName, info.versionName);

        } catch(NameNotFoundException e) {
            Log.e(TAG, "Couldn't find package information in PackageManager", e);
        }
    }

    /**
     * Create a URL from an API call (or "action string")
     */
    public static String getPageURL(String call) {
        Log.i("Jarvis", String.format("Call: %s", call));

        // Call's parts
        String function = "";
        String action = "";
        String data = "";

        // Final URL
        String url = "";
        Boolean isInternal = true;

        // This is weird left-over support for external URLs
        // Check if this is not a Jarvis URL
        if (call.length() >= 4 && call.substring(0, 4).equals("http")) {
            Integer length = API_ROOT.length();
            if (call.substring(0, length).equals(API_ROOT)) {
                call = call.substring(length+1);
                call = call.replace("/", " ");
            } else {
                url = call;
                isInternal = false;
            }
        }

        if (isInternal) {
            // Encode URI data
            Pattern pattern = Pattern.compile(" ");
            String[] parts = pattern.split(call, 3);
            function = Uri.encode(parts[0]);
            if (parts.length > 1) {
                action = Uri.encode(parts[1]);
            }
            if (parts.length > 2) {
                data = Uri.encode(parts[2]);
            }

            // Final format
            url = String.format("%s/api/%s/%s/%s", API_ROOT, function, action, data);
        }

        Log.d("Jarvis", String.format("URL: %s", url));

        return url;
    }

    /**
     * Read and return the content for a specific API call.
     * Because this call blocks until results are available, it should not be
     * run from a UI thread.
     *
     * @param call API call
     * @return JSON response as string
     * @throws ApiException If any connection or server error occurs.
     * @throws ParseException If there are problems parsing the response.
     */
    public static String getPageContent(String call)
            throws ApiException, ParseException {

        // Generate URL
        String url = getPageURL(call);

        // Get and return content of response
        String content = getUrlContent(url);
        return content;
    }

    /**
     * Pull the raw text content of the given URL. This call blocks until the
     * operation has completed, and is synchronised because it uses a shared
     * buffer {@link #sBuffer}.
     *
     * @param url The exact URL to request.
     * @return The raw content returned by the server.
     * @throws ApiException If any connection or server error occurs.
     */
    protected static synchronized String getUrlContent(String url) throws ApiException {
        if (sUserAgent == null) {
            throw new ApiException("User-Agent string must be prepared");
        }

        // Create client and set our specific user-agent string
        HttpClient httpClient = new DefaultHttpClient();
        HttpGet getRequest = new HttpGet(url);
        getRequest.setHeader("User-Agent", sUserAgent);
        getRequest.setHeader("secret", API_SECRET);
        getRequest.setHeader("Client-UID", ""); //TODO

        try {
            HttpResponse response = httpClient.execute(getRequest);

            // Pull content stream from response
            HttpEntity entity = response.getEntity();
            InputStream inputStream = entity.getContent();

            ByteArrayOutputStream content = new ByteArrayOutputStream();

            // Read response into a buffered stream
            int readBytes = 0;
            while ((readBytes = inputStream.read(sBuffer)) != -1) {
                content.write(sBuffer, 0, readBytes);
            }

            // Return result from buffered stream
            return new String(content.toByteArray());
        } catch (IOException e) {
            throw new ApiException("Problem communicating with API: " + e.getMessage());
        }
    }
}
