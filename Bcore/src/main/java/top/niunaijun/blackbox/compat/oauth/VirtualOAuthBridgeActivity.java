package top.niunaijun.blackbox.compat.oauth;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import android.util.Base64;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import top.niunaijun.blackbox.compat.auth.ExternalAuthRouter;
import top.niunaijun.blackbox.compat.auth.GoogleSignInTokenHelper;
import top.niunaijun.blackbox.proxy.ProxyManifest;
import top.niunaijun.blackbox.utils.compat.BundleCompat;
import top.niunaijun.blackbox.utils.provider.ProviderCall;

/**
 * Host-side trampoline providing 1-Tap Native Google Account Picker
 * and Web Sign-In fallback for guest apps inside the virtual container.
 */
public final class VirtualOAuthBridgeActivity extends Activity {
    private static final String TAG = "EliteOAuth";
    private static final int REQUEST_EXTERNAL_AUTH = 0x5042;
    private static final int REQUEST_CHOOSE_GOOGLE_ACCOUNT = 0x5043;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private String virtualPackage;
    private int userId = -1;
    private int resultBpid = -1;
    private boolean externalAuthMode;
    private boolean callbackDispatched;
    private WebView embeddedWebView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureCompactWindow();

        Intent launchIntent = getIntent();
        boolean googleWebAuthMode = launchIntent != null
                && launchIntent.getBooleanExtra(ExternalAuthRouter.EXTRA_GOOGLE_WEB_AUTH, false);
        externalAuthMode = launchIntent != null
                && launchIntent.getBooleanExtra(ExternalAuthRouter.EXTRA_EXTERNAL_AUTH, false);
        resultBpid = launchIntent == null ? -1
                : launchIntent.getIntExtra(ExternalAuthRouter.EXTRA_BPID, -1);

        if (googleWebAuthMode) {
            virtualPackage = launchIntent.getStringExtra(
                    ExternalAuthRouter.EXTRA_VIRTUAL_PACKAGE);
            userId = launchIntent.getIntExtra(ExternalAuthRouter.EXTRA_USER_ID, -1);
            if (!validResultTarget(launchIntent)) {
                finish();
                return;
            }
            String serverClientId = launchIntent.getStringExtra(
                    ExternalAuthRouter.EXTRA_GOOGLE_SERVER_CLIENT_ID);
            launchGoogleNativeAccountPickerOrWebView(serverClientId);
            return;
        }

        if (externalAuthMode) {
            virtualPackage = launchIntent.getStringExtra(
                    ExternalAuthRouter.EXTRA_VIRTUAL_PACKAGE);
            userId = launchIntent.getIntExtra(ExternalAuthRouter.EXTRA_USER_ID, -1);
            if (!validResultTarget(launchIntent)) {
                finish();
                return;
            }
            if (savedInstanceState == null) {
                launchExternalProvider(launchIntent);
            }
            return;
        }

        finish();
    }

    private void configureCompactWindow() {
        try {
            android.view.Window window = getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                window.setGravity(Gravity.CENTER);
                android.view.WindowManager.LayoutParams params = window.getAttributes();
                android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                int minDim = Math.min(dm.widthPixels, dm.heightPixels);
                params.width = (int) (minDim * 0.90f);
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                window.setAttributes(params);
            }
        } catch (Throwable ignored) {
        }
    }

    private void launchGoogleNativeAccountPickerOrWebView(final String serverClientId) {
        try {
            AccountManager am = AccountManager.get(this);
            Account[] accounts = null;
            try {
                accounts = am.getAccountsByType("com.google");
            } catch (Throwable ignored) {
            }

            if (accounts != null && accounts.length > 0) {
                showPremiumGoogleAccountPicker(accounts, serverClientId);
                return;
            }

            launchSystemGoogleAccountPicker(serverClientId);
        } catch (Throwable t) {
            launchGoogleWebOAuthFlow(serverClientId, null);
        }
    }

    private void launchSystemGoogleAccountPicker(final String serverClientId) {
        try {
            Intent chooseIntent = null;
            for (java.lang.reflect.Method m : AccountManager.class.getMethods()) {
                if ("newChooseAccountIntent".equals(m.getName())) {
                    Class<?>[] pTypes = m.getParameterTypes();
                    if (pTypes.length == 7) {
                        chooseIntent = (Intent) m.invoke(null, null, null, new String[]{"com.google"}, null, null, null, null);
                        break;
                    } else if (pTypes.length == 8) {
                        chooseIntent = (Intent) m.invoke(null, null, null, new String[]{"com.google"}, false, null, null, null, null);
                        break;
                    }
                }
            }
            if (chooseIntent != null && chooseIntent.resolveActivity(getPackageManager()) != null) {
                startActivityForResult(chooseIntent, REQUEST_CHOOSE_GOOGLE_ACCOUNT);
                return;
            }
        } catch (Throwable ignored) {
        }
        launchGoogleWebOAuthFlow(serverClientId, null);
    }

    private void showPremiumGoogleAccountPicker(final Account[] accounts, final String serverClientId) {
        try {
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER_HORIZONTAL);
            root.setPadding(dp(16), dp(18), dp(16), dp(18));

            android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
            cardBg.setColor(Color.parseColor("#150F24"));
            cardBg.setCornerRadius(dp(24));
            cardBg.setStroke(dp(2), Color.parseColor("#4A2E70"));
            root.setBackground(cardBg);

            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.VERTICAL);
            header.setGravity(Gravity.CENTER_HORIZONTAL);
            header.setPadding(0, dp(4), 0, dp(14));

            TextView logoView = new TextView(this);
            logoView.setText("G");
            logoView.setTextSize(22f);
            logoView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            logoView.setGravity(Gravity.CENTER);
            logoView.setTextColor(Color.WHITE);
            android.graphics.drawable.GradientDrawable logoBg = new android.graphics.drawable.GradientDrawable();
            logoBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            logoBg.setColors(new int[]{Color.parseColor("#A855F7"), Color.parseColor("#EC4899")});
            logoView.setBackground(logoBg);
            int logoSize = dp(44);
            header.addView(logoView, new LinearLayout.LayoutParams(logoSize, logoSize));

            TextView titleView = new TextView(this);
            titleView.setText("Choose Google Account");
            titleView.setTextColor(Color.parseColor("#F8F5FF"));
            titleView.setTextSize(16.5f);
            titleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            titleView.setPadding(0, dp(10), 0, dp(2));
            header.addView(titleView);

            TextView subtitleView = new TextView(this);
            subtitleView.setText("1-Tap to continue to game");
            subtitleView.setTextColor(Color.parseColor("#A99BBE"));
            subtitleView.setTextSize(12.5f);
            header.addView(subtitleView);

            root.addView(header, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            android.view.View divider = new android.view.View(this);
            divider.setBackgroundColor(Color.parseColor("#281C3D"));
            root.addView(divider, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

            android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
            LinearLayout listContainer = new LinearLayout(this);
            listContainer.setOrientation(LinearLayout.VERTICAL);
            listContainer.setPadding(0, dp(8), 0, dp(8));

            int[][] avatarGradients = new int[][]{
                    {Color.parseColor("#A855F7"), Color.parseColor("#7C3AED")},
                    {Color.parseColor("#EC4899"), Color.parseColor("#BE185D")},
                    {Color.parseColor("#3B82F6"), Color.parseColor("#1D4ED8")},
                    {Color.parseColor("#10B981"), Color.parseColor("#047857")},
                    {Color.parseColor("#F59E0B"), Color.parseColor("#B45309")}
            };

            for (int i = 0; i < accounts.length; i++) {
                final Account account = accounts[i];
                final String email = account.name;
                String initial = (email != null && !email.isEmpty())
                        ? email.substring(0, 1).toUpperCase(Locale.US) : "G";
                final String userName = (email != null && email.contains("@"))
                        ? email.split("@")[0] : "Google User";

                LinearLayout itemRow = new LinearLayout(this);
                itemRow.setOrientation(LinearLayout.HORIZONTAL);
                itemRow.setGravity(Gravity.CENTER_VERTICAL);
                itemRow.setPadding(dp(12), dp(10), dp(12), dp(10));
                itemRow.setClickable(true);
                itemRow.setFocusable(true);

                android.graphics.drawable.GradientDrawable itemBg = new android.graphics.drawable.GradientDrawable();
                itemBg.setColor(Color.parseColor("#1E1730"));
                itemBg.setCornerRadius(dp(14));
                itemBg.setStroke(dp(1), Color.parseColor("#382352"));
                itemRow.setBackground(itemBg);

                TextView avatar = new TextView(this);
                avatar.setText(initial);
                avatar.setTextSize(16f);
                avatar.setTextColor(Color.WHITE);
                avatar.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                avatar.setGravity(Gravity.CENTER);
                android.graphics.drawable.GradientDrawable avBg = new android.graphics.drawable.GradientDrawable();
                avBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                int[] grad = avatarGradients[i % avatarGradients.length];
                avBg.setColors(grad);
                avatar.setBackground(avBg);
                int avSize = dp(38);
                itemRow.addView(avatar, new LinearLayout.LayoutParams(avSize, avSize));

                LinearLayout textCol = new LinearLayout(this);
                textCol.setOrientation(LinearLayout.VERTICAL);
                textCol.setPadding(dp(12), 0, 0, 0);

                TextView nameTv = new TextView(this);
                nameTv.setText(userName);
                nameTv.setTextSize(14f);
                nameTv.setTextColor(Color.parseColor("#F8F5FF"));
                nameTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                textCol.addView(nameTv);

                TextView emailTv = new TextView(this);
                emailTv.setText(email);
                emailTv.setTextSize(12f);
                emailTv.setTextColor(Color.parseColor("#A99BBE"));
                textCol.addView(emailTv);

                LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                itemRow.addView(textCol, textLp);

                TextView arrow = new TextView(this);
                arrow.setText("➔");
                arrow.setTextColor(Color.parseColor("#A855F7"));
                arrow.setTextSize(14f);
                itemRow.addView(arrow);

                itemRow.setOnClickListener(v -> {
                    try {
                        android.widget.Toast.makeText(this, "Signing in with " + email + "...", android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {
                    }
                    launchGoogleWebOAuthFlow(serverClientId, email);
                });

                LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                itemLp.setMargins(0, dp(4), 0, dp(4));
                listContainer.addView(itemRow, itemLp);
            }

            scrollView.addView(listContainer, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            root.addView(scrollView, scrollLp);

            LinearLayout footer = new LinearLayout(this);
            footer.setOrientation(LinearLayout.HORIZONTAL);
            footer.setGravity(Gravity.CENTER_VERTICAL);
            footer.setPadding(0, dp(12), 0, dp(4));

            TextView cancelBtn = new TextView(this);
            cancelBtn.setText("Cancel");
            cancelBtn.setTextColor(Color.parseColor("#A99BBE"));
            cancelBtn.setTextSize(13f);
            cancelBtn.setPadding(dp(10), dp(8), dp(10), dp(8));
            cancelBtn.setOnClickListener(v -> completeBridgeResult(RESULT_CANCELED, null));
            footer.addView(cancelBtn);

            TextView allAccountsBtn = new TextView(this);
            allAccountsBtn.setText("All Accounts ▾");
            allAccountsBtn.setTextColor(Color.parseColor("#38BDF8"));
            allAccountsBtn.setTextSize(13f);
            allAccountsBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            allAccountsBtn.setPadding(dp(10), dp(8), dp(10), dp(8));
            allAccountsBtn.setOnClickListener(v -> launchSystemGoogleAccountPicker(serverClientId));
            footer.addView(allAccountsBtn);

            android.view.View spacer = new android.view.View(this);
            footer.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

            TextView useAnotherBtn = new TextView(this);
            useAnotherBtn.setText("+ Web Login");
            useAnotherBtn.setTextColor(Color.parseColor("#A855F7"));
            useAnotherBtn.setTextSize(13f);
            useAnotherBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            useAnotherBtn.setPadding(dp(10), dp(8), dp(10), dp(8));
            useAnotherBtn.setOnClickListener(v -> launchGoogleWebOAuthFlow(serverClientId, null));
            footer.addView(useAnotherBtn);

            root.addView(footer, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            setContentView(root);
        } catch (Throwable t) {
            launchGoogleWebOAuthFlow(serverClientId, null);
        }
    }

    private void expandWindowForWebView() {
        try {
            android.view.Window window = getWindow();
            if (window != null) {
                android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                int minDim = Math.min(dm.widthPixels, dm.heightPixels);
                int maxDim = Math.max(dm.widthPixels, dm.heightPixels);
                android.view.WindowManager.LayoutParams params = window.getAttributes();
                params.width = (int) (minDim * 0.95f);
                params.height = (int) (maxDim * 0.85f);
                window.setAttributes(params);
            }
        } catch (Throwable ignored) {
        }
    }

    @SuppressLint({"SetJavaScriptEnabled"})
    private void launchGoogleWebOAuthFlow(final String serverClientId, final String emailHint) {
        try {
            expandWindowForWebView();

            final String clientId = (serverClientId != null && !serverClientId.trim().isEmpty())
                    ? serverClientId.trim() : "646362697320-ng2m2uj8a53arsiqgk7btc3bq9ia3rep.apps.googleusercontent.com";
            final String clientIdPrefix = clientId.contains(".") ? clientId.substring(0, clientId.indexOf('.')) : clientId;
            final String redirectUri = "com.googleusercontent.apps." + clientIdPrefix + ":/oauth2redirect";

            StringBuilder urlBuilder = new StringBuilder("https://accounts.google.com/o/oauth2/v2/auth?");
            urlBuilder.append("client_id=").append(URLEncoder.encode(clientId, "UTF-8"));
            urlBuilder.append("&response_type=").append(URLEncoder.encode("code id_token", "UTF-8"));
            urlBuilder.append("&scope=").append(URLEncoder.encode("openid email profile", "UTF-8"));
            urlBuilder.append("&redirect_uri=").append(URLEncoder.encode(redirectUri, "UTF-8"));
            urlBuilder.append("&nonce=").append(System.currentTimeMillis());
            if (emailHint != null && !emailHint.trim().isEmpty()) {
                urlBuilder.append("&login_hint=").append(URLEncoder.encode(emailHint.trim(), "UTF-8"));
            }
            final String googleOAuthUrl = urlBuilder.toString();
            Log.d(TAG, "Launching Google OAuth URL: " + googleOAuthUrl);

            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            android.graphics.drawable.GradientDrawable compactBg = new android.graphics.drawable.GradientDrawable();
            compactBg.setColor(Color.parseColor("#F51C1C1E"));
            compactBg.setCornerRadius(dp(22));
            compactBg.setStroke(dp(1), Color.parseColor("#33FFFFFF"));
            root.setBackground(compactBg);

            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setBackgroundColor(Color.parseColor("#1C1C1E"));
            int padH = dp(14);
            int padV = dp(10);
            header.setPadding(padH, padV, padH, padV);

            TextView titleView = new TextView(this);
            titleView.setText(emailHint != null ? "Signing in (" + emailHint.split("@")[0] + ")..." : "Google Sign-In");
            titleView.setTextColor(Color.WHITE);
            titleView.setTextSize(14.5f);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            header.addView(titleView, titleLp);

            TextView closeBtn = new TextView(this);
            closeBtn.setText("Close");
            closeBtn.setTextColor(Color.WHITE);
            closeBtn.setTextSize(13f);
            closeBtn.setPadding(dp(10), dp(2), dp(4), dp(2));
            closeBtn.setOnClickListener(v -> completeBridgeResult(RESULT_CANCELED, null));
            header.addView(closeBtn);

            root.addView(header, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            final ProgressBar progressBar = new ProgressBar(
                    this, null, android.R.attr.progressBarStyleHorizontal);
            progressBar.setMax(100);
            root.addView(progressBar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

            FrameLayout webContainer = new FrameLayout(this);
            embeddedWebView = new WebView(this);
            WebSettings settings = embeddedWebView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setDatabaseEnabled(true);
            settings.setJavaScriptCanOpenWindowsAutomatically(true);
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            // Chrome Mobile User-Agent so Google accounts endpoint allows standard authentication
            settings.setUserAgentString(
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.200 Mobile Safari/537.36");

            CookieManager cm = CookieManager.getInstance();
            if (cm != null) {
                cm.setAcceptCookie(true);
                cm.setAcceptThirdPartyCookies(embeddedWebView, true);
            }

            embeddedWebView.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onProgressChanged(WebView view, int newProgress) {
                    progressBar.setProgress(newProgress);
                    progressBar.setVisibility(newProgress >= 100
                            ? android.view.View.GONE : android.view.View.VISIBLE);
                }
            });

            embeddedWebView.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    if (handleOAuthRedirectUrl(url, emailHint)) {
                        return true;
                    }
                    return super.shouldOverrideUrlLoading(view, url);
                }

                @Override
                public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                    super.onPageStarted(view, url, favicon);
                    if (handleOAuthRedirectUrl(url, emailHint)) {
                        view.stopLoading();
                    }
                }
            });

            webContainer.addView(embeddedWebView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(webContainer, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            setContentView(root);
            embeddedWebView.loadUrl(googleOAuthUrl);
        } catch (Throwable t) {
            Log.e(TAG, "launchGoogleWebOAuthFlow failed", t);
            completeBridgeResult(RESULT_CANCELED, null);
        }
    }

    private boolean handleOAuthRedirectUrl(String url, String fallbackEmail) {
        if (url == null || callbackDispatched) return false;
        String lower = url.toLowerCase(Locale.US);
        if (lower.startsWith("com.googleusercontent.apps") || lower.contains(":/oauth2redirect")) {
            callbackDispatched = true;
            try {
                Log.d(TAG, "Intercepted Google OAuth redirect: " + url);
                String idToken = extractParamFromUrl(url, "id_token");
                String authCode = extractParamFromUrl(url, "code");
                String error = extractParamFromUrl(url, "error");

                if (error != null) {
                    Log.w(TAG, "Google OAuth returned error: " + error);
                    completeBridgeResult(RESULT_CANCELED, null);
                    return true;
                }

                String email = null;
                String name = null;
                String sub = null;

                if (idToken != null && idToken.contains(".")) {
                    try {
                        String[] parts = idToken.split("\\.");
                        if (parts.length >= 2) {
                            byte[] decoded = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
                            JSONObject json = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
                            email = json.optString("email", null);
                            name = json.optString("name", null);
                            sub = json.optString("sub", null);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed to parse JWT payload: " + t.getMessage());
                    }
                }

                if (email == null) email = fallbackEmail;
                if (name == null && email != null && email.contains("@")) name = email.split("@")[0];

                finishGoogleWebSignInWithTokens(email, name, sub, idToken, authCode);
                return true;
            } catch (Throwable t) {
                Log.e(TAG, "handleOAuthRedirectUrl error", t);
                completeBridgeResult(RESULT_CANCELED, null);
                return true;
            }
        }
        return false;
    }

    private static String extractParamFromUrl(String url, String param) {
        if (url == null || param == null) return null;
        try {
            String queryAndFragment = url;
            int qIdx = url.indexOf('?');
            int fIdx = url.indexOf('#');
            int startIdx = (qIdx != -1) ? qIdx : fIdx;
            if (startIdx != -1) {
                queryAndFragment = url.substring(startIdx + 1);
            }
            String[] tokens = queryAndFragment.split("[&#]");
            for (String token : tokens) {
                int eq = token.indexOf('=');
                if (eq > 0) {
                    String k = token.substring(0, eq).trim();
                    if (param.equalsIgnoreCase(k)) {
                        String v = token.substring(eq + 1).trim();
                        return URLDecoder.decode(v, "UTF-8");
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void finishGoogleWebSignInWithTokens(String email, String displayName, String subId, String idToken, String authCode) {
        try {
            String safeEmail = (email != null && email.contains("@")) ? email.trim() : "player@gmail.com";
            String safeName = (displayName != null && !displayName.trim().isEmpty()
                    && !displayName.contains("Google Account"))
                    ? displayName.trim() : safeEmail.split("@")[0];
            String safeSub = (subId != null && !subId.trim().isEmpty())
                    ? subId.trim() : String.valueOf(Math.abs((long) safeEmail.hashCode()) + 100000000000000000L);

            Log.i(TAG, "Google sign-in complete. Email: " + safeEmail + ", hasIdToken: " + (idToken != null) + ", hasAuthCode: " + (authCode != null));

            Intent resultIntent = new Intent();
            resultIntent.putExtra(GoogleSignInTokenHelper.EXTRA_WEB_EMAIL, safeEmail);
            resultIntent.putExtra(GoogleSignInTokenHelper.EXTRA_WEB_NAME, safeName);
            resultIntent.putExtra(GoogleSignInTokenHelper.EXTRA_WEB_SUB, safeSub);
            if (idToken != null && !idToken.trim().isEmpty()) {
                resultIntent.putExtra(GoogleSignInTokenHelper.EXTRA_WEB_ID_TOKEN, idToken.trim());
            }
            if (authCode != null && !authCode.trim().isEmpty()) {
                resultIntent.putExtra(GoogleSignInTokenHelper.EXTRA_WEB_AUTH_CODE, authCode.trim());
            }

            GoogleSignInTokenHelper.enrichSignInResultIntent(resultIntent, true);
            completeBridgeResult(RESULT_OK, resultIntent);
        } catch (Throwable ignored) {
            completeBridgeResult(RESULT_CANCELED, null);
        }
    }

    private void finishGoogleWebSignInWithProfile(String email, String displayName) {
        finishGoogleWebSignInWithTokens(email, displayName, null, null, null);
    }

    private void launchExternalProvider(Intent bridgeIntent) {
        try {
            Intent providerIntent = bridgeIntent.getParcelableExtra(
                    ExternalAuthRouter.EXTRA_PROVIDER_INTENT);
            if (providerIntent != null) {
                startActivityForResult(providerIntent, REQUEST_EXTERNAL_AUTH);
                return;
            }
        } catch (Throwable ignored) {
        }
        completeBridgeResult(RESULT_CANCELED, null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CHOOSE_GOOGLE_ACCOUNT) {
            if (resultCode == RESULT_OK && data != null) {
                String accountName = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
                if (accountName != null && accountName.contains("@")) {
                    String serverClientId = getIntent() != null ? getIntent().getStringExtra(ExternalAuthRouter.EXTRA_GOOGLE_SERVER_CLIENT_ID) : null;
                    launchGoogleWebOAuthFlow(serverClientId, accountName.trim());
                    return;
                }
            }
            completeBridgeResult(RESULT_CANCELED, null);
            return;
        }

        if (requestCode == REQUEST_EXTERNAL_AUTH && externalAuthMode) {
            completeBridgeResult(resultCode, data);
        }
    }

    private boolean completeBridgeResult(int resultCode, Intent data) {
        setResult(resultCode, data);
        boolean delivered = relayOriginalActivityResult(resultCode, data);
        finish();
        overridePendingTransition(0, 0);
        return delivered;
    }

    private boolean validResultTarget(Intent launchIntent) {
        if (launchIntent == null || resultBpid < 0 || resultBpid > 50) {
            return false;
        }
        Bundle extras = launchIntent.getExtras();
        if (extras == null) {
            return false;
        }
        IBinder resultTo = extras.getBinder(ExternalAuthRouter.EXTRA_RESULT_BINDER);
        int requestCode = extras.getInt(ExternalAuthRouter.EXTRA_REQUEST_CODE, -1);
        String targetPackage = extras.getString(ExternalAuthRouter.EXTRA_VIRTUAL_PACKAGE);
        return resultTo != null
                && requestCode >= 0
                && targetPackage != null
                && !targetPackage.trim().isEmpty();
    }

    private boolean relayOriginalActivityResult(int resultCode, Intent data) {
        try {
            Intent bridgeIntent = getIntent();
            Bundle extras = bridgeIntent == null ? null : bridgeIntent.getExtras();
            if (extras == null || resultBpid < 0 || resultBpid > 50) {
                return false;
            }

            IBinder resultTo = extras.getBinder(ExternalAuthRouter.EXTRA_RESULT_BINDER);
            String resultWho = extras.getString(ExternalAuthRouter.EXTRA_RESULT_WHO);
            int originalRequestCode = extras.getInt(
                    ExternalAuthRouter.EXTRA_REQUEST_CODE, -1);
            String targetPackage = extras.getString(
                    ExternalAuthRouter.EXTRA_VIRTUAL_PACKAGE);
            if (resultTo == null || originalRequestCode < 0
                    || targetPackage == null || targetPackage.trim().isEmpty()) {
                return false;
            }

            Bundle relay = new Bundle();
            BundleCompat.putBinder(
                    relay, ExternalAuthRouter.EXTRA_RESULT_BINDER, resultTo);
            relay.putString(ExternalAuthRouter.EXTRA_RESULT_WHO, resultWho);
            relay.putInt(ExternalAuthRouter.EXTRA_REQUEST_CODE, originalRequestCode);
            relay.putInt(ExternalAuthRouter.EXTRA_RESULT_CODE, resultCode);
            relay.putInt(ExternalAuthRouter.EXTRA_BPID, resultBpid);
            relay.putInt(ExternalAuthRouter.EXTRA_USER_ID, userId);
            relay.putString(ExternalAuthRouter.EXTRA_VIRTUAL_PACKAGE, targetPackage);
            if (data != null) {
                relay.putParcelable(
                        ExternalAuthRouter.EXTRA_RESULT_DATA, new Intent(data));
            }

            Bundle response = ProviderCall.callSafely(
                    ProxyManifest.getProxyAuthorities(resultBpid),
                    ExternalAuthRouter.METHOD_DELIVER_ACTIVITY_RESULT,
                    null,
                    relay);
            return response != null
                    && response.getBoolean(
                    ExternalAuthRouter.EXTRA_RESULT_DELIVERED, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int dp(float dip) {
        return (int) (dip * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        if (embeddedWebView != null) {
            try {
                embeddedWebView.stopLoading();
                embeddedWebView.destroy();
            } catch (Throwable ignored) {
            }
            embeddedWebView = null;
        }
        super.onDestroy();
    }
}
