package top.niunaijun.blackbox.fake.delegate;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Build;
import android.os.PersistableBundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.lang.reflect.Field;

import black.android.app.BRActivity;
import black.android.app.BRActivityThread;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.HookManager;
import top.niunaijun.blackbox.fake.hook.IInjectHook;
import top.niunaijun.blackbox.fake.service.HCallbackProxy;
import top.niunaijun.blackbox.fake.service.IActivityClientProxy;
import top.niunaijun.blackbox.utils.HackAppUtils;
import top.niunaijun.blackbox.utils.FacebookWebViewViewportFix;
import top.niunaijun.blackbox.utils.compat.ActivityCompat;
import top.niunaijun.blackbox.utils.compat.ActivityManagerCompat;
import top.niunaijun.blackbox.utils.compat.ContextCompat;

public final class AppInstrumentation extends BaseInstrumentationDelegate implements IInjectHook {

    private static final String TAG = AppInstrumentation.class.getSimpleName();

    private static AppInstrumentation sAppInstrumentation;

    public static AppInstrumentation get() {
        if (sAppInstrumentation == null) {
            synchronized (AppInstrumentation.class) {
                if (sAppInstrumentation == null) {
                    sAppInstrumentation = new AppInstrumentation();
                }
            }
        }
        return sAppInstrumentation;
    }

    public AppInstrumentation() {
    }

    @Override
    public void injectHook() {
        try {
            Instrumentation mInstrumentation = getCurrInstrumentation();
            if (mInstrumentation == this || checkInstrumentation(mInstrumentation))
                return;
            mBaseInstrumentation = (Instrumentation) mInstrumentation;
            BRActivityThread.get(BlackBoxCore.mainThread())._set_mInstrumentation(this);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Instrumentation getCurrInstrumentation() {
        Object currentActivityThread = BlackBoxCore.mainThread();
        return BRActivityThread.get(currentActivityThread).mInstrumentation();
    }

    @Override
    public boolean isBadEnv() {
        return !checkInstrumentation(getCurrInstrumentation());
    }

    private boolean checkInstrumentation(Instrumentation instrumentation) {
        if (instrumentation instanceof AppInstrumentation) {
            return true;
        }
        Class<?> clazz = instrumentation.getClass();
        if (Instrumentation.class.equals(clazz)) {
            return false;
        }
        do {
            assert clazz != null;
            Field[] fields = clazz.getDeclaredFields();
            for (Field field : fields) {
                if (Instrumentation.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    try {
                        Object obj = field.get(instrumentation);
                        if ((obj instanceof AppInstrumentation)) {
                            return true;
                        }
                    } catch (Exception e) {
                        return false;
                    }
                }
            }
            clazz = clazz.getSuperclass();
        } while (!Instrumentation.class.equals(clazz));
        return false;
    }

    private void checkHCallback() {
        HookManager.get().checkEnv(HCallbackProxy.class);
    }

    private void checkActivity(Activity activity) {
        Log.d(TAG, "callActivityOnCreate: " + activity.getClass().getName());
        HackAppUtils.enableQQLogOutput(activity.getPackageName(), activity.getClassLoader());
        checkHCallback();
        HookManager.get().checkEnv(IActivityClientProxy.class);
        ActivityInfo info = BRActivity.get(activity).mActivityInfo();
        ContextCompat.fix(activity);
        ActivityCompat.fix(activity);
        if (info.theme != 0) {
            activity.getTheme().applyStyle(info.theme, true);
        }
        ActivityManagerCompat.setActivityOrientation(activity, info.screenOrientation);
    }

    @Override
    public Application newApplication(ClassLoader cl, String className, Context context) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        ContextCompat.fix(context);

        return super.newApplication(cl, className, context);
    }

    /**
     * Twitter/X OAuth compatibility copied from the known-working WebView SDK.
     * It is deliberately scoped only to TwitterWebActivity so the existing
     * Facebook Custom Tab/WebView flow remains unchanged.
     */
    private void tuneTwitterLoginWebView(Activity activity) {
        if (activity == null || !"com.itop.twitterwrapper.TwitterWebActivity".equals(activity.getClass().getName())) {
            return;
        }
        try {
            final View root = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (root == null) return;

            Runnable tuner = () -> {
                try {
                    int count = tuneTwitterWebViewsRecursive(root);
                    Log.d(TAG, "Twitter WebView targeted tune, count=" + count);
                } catch (Throwable e) {
                    Log.w(TAG, "Twitter WebView targeted tune failed", e);
                }
            };

            // First pass is synchronous so OAuth navigation does not enter
            // ERR_CACHE_MISS before the settings are applied.  Delayed passes
            // catch wrappers that attach/replace the WebView asynchronously.
            tuner.run();
            root.post(tuner);
            root.postDelayed(tuner, 150L);
            root.postDelayed(tuner, 500L);
            root.postDelayed(tuner, 1000L);
        } catch (Throwable e) {
            Log.w(TAG, "Unable to schedule Twitter WebView tune", e);
        }
    }

    private int tuneTwitterWebViewsRecursive(View view) {
        if (view == null) return 0;
        int count = 0;

        if (view instanceof WebView) {
            WebView webView = (WebView) view;
            WebSettings settings = null;
            int applied = 0;

            try {
                settings = webView.getSettings();
            } catch (Throwable e) {
                Log.w(TAG, "Twitter WebView getSettings failed", e);
            }

            if (settings != null) {
                // Do not call setBlockNetworkLoads(false): Android 16 can make
                // WebView re-check INTERNET through the virtual UID and throw.
                try { settings.setCacheMode(WebSettings.LOAD_DEFAULT); applied++; }
                catch (Throwable e) { Log.w(TAG, "Twitter WebView cacheMode failed", e); }

                try { settings.setBlockNetworkImage(false); applied++; }
                catch (Throwable e) { Log.w(TAG, "Twitter WebView networkImage failed", e); }

                try { settings.setLoadsImagesAutomatically(true); applied++; }
                catch (Throwable e) { Log.w(TAG, "Twitter WebView autoImages failed", e); }

                try { settings.setJavaScriptEnabled(true); applied++; }
                catch (Throwable e) { Log.w(TAG, "Twitter WebView JavaScript failed", e); }

                try { settings.setJavaScriptCanOpenWindowsAutomatically(true); applied++; }
                catch (Throwable e) { Log.w(TAG, "Twitter WebView JS windows failed", e); }

                try { settings.setDomStorageEnabled(true); applied++; }
                catch (Throwable e) { Log.w(TAG, "Twitter WebView DOM storage failed", e); }

                try { settings.setDatabaseEnabled(true); applied++; }
                catch (Throwable e) { Log.w(TAG, "Twitter WebView database failed", e); }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    try { settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE); applied++; }
                    catch (Throwable e) { Log.w(TAG, "Twitter WebView mixedContent failed", e); }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try { settings.setSafeBrowsingEnabled(false); applied++; }
                    catch (Throwable e) { Log.w(TAG, "Twitter WebView safeBrowsing setting failed", e); }
                }
            }

            try {
                CookieManager cm = CookieManager.getInstance();
                cm.setAcceptCookie(true);
                applied++;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    try { cm.setAcceptThirdPartyCookies(webView, true); applied++; }
                    catch (Throwable e) { Log.w(TAG, "Twitter WebView third-party cookies failed", e); }
                    try { cm.flush(); applied++; }
                    catch (Throwable e) { Log.w(TAG, "Twitter WebView cookie flush failed", e); }
                }
            } catch (Throwable e) {
                Log.w(TAG, "Twitter WebView CookieManager failed", e);
            }

            if (applied > 0) {
                count++;
                Log.d(TAG, "Twitter WebView instance tuned, appliedSettings=" + applied);
            } else {
                Log.w(TAG, "Twitter WebView instance found but no settings applied");
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                count += tuneTwitterWebViewsRecursive(group.getChildAt(i));
            }
        }
        return count;
    }

    private static void prepareGoogleSignInHubBeforeCreate(Activity activity) {
        if (activity == null) return;
        String name = activity.getClass().getName();
        if ("com.google.android.gms.auth.api.signin.internal.SignInHubActivity".equals(name)) {
            try {
                Intent intent = activity.getIntent();
                if (intent != null) {
                    if (BActivityThread.getApplication() != null) {
                        intent.setExtrasClassLoader(
                                BActivityThread.getApplication().getClassLoader());
                    }
                    if ("com.google.android.gms.auth.NO_IMPL".equals(intent.getAction())) {
                        intent.setAction("com.google.android.gms.auth.GOOGLE_SIGN_IN");
                    }
                    top.niunaijun.blackbox.compat.auth.GoogleSignInTokenHelper.prepareOutboundSignInIntent(
                            intent,
                            BActivityThread.getAppPackageName(),
                            BlackBoxCore.getHostPkg());
                }
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle, PersistableBundle persistentState) {
        checkActivity(activity);
        prepareGoogleSignInHubBeforeCreate(activity);
        super.callActivityOnCreate(activity, icicle, persistentState);
        FacebookWebViewViewportFix.applyIfNeeded(activity);
        tuneTwitterLoginWebView(activity);
    }

    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle) {
        checkActivity(activity);
        prepareGoogleSignInHubBeforeCreate(activity);
        super.callActivityOnCreate(activity, icicle);
        FacebookWebViewViewportFix.applyIfNeeded(activity);
        tuneTwitterLoginWebView(activity);
    }

    @Override
    public void callActivityOnResume(Activity activity) {
        super.callActivityOnResume(activity);
        FacebookWebViewViewportFix.applyIfNeeded(activity);
    }

    @Override
    public void callApplicationOnCreate(Application app) {
        checkHCallback();
        super.callApplicationOnCreate(app);
    }

    public Activity newActivity(ClassLoader cl, String className, Intent intent) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        try {
            return super.newActivity(cl, className, intent);
        } catch (ClassNotFoundException e) {
            return mBaseInstrumentation.newActivity(cl, className, intent);
        }
    }
}
