package top.niunaijun.blackbox.utils;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import java.util.List;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.proxy.FacebookLoginKeepAliveService;

/**
 * Keeps Facebook Custom Tab login attached to the virtual guest instead of allowing
 * the browser callback to fall through to a separately-installed real application.
 * No credentials or access tokens are read or stored here; the callback URI is relayed
 * unchanged back to the virtual package.
 */
public final class FacebookLoginCompat {
    private static final String TAG = "FacebookLoginCompat";
    private static final String PREFS = "bbox_facebook_callback_bridge";
    private static final String KEY_GUEST_PACKAGE = "guest_package";
    private static final String KEY_USER_ID = "user_id";

    private FacebookLoginCompat() {
    }

    public static void prepareExternalLogin(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) {
            return;
        }
        Uri data = intent.getData();
        if (data == null) {
            return;
        }
        String host = data.getHost();
        if (host == null || !host.toLowerCase().contains("facebook.com")) {
            return;
        }

        String guestPackage = BActivityThread.getAppPackageName();
        if (guestPackage == null || guestPackage.length() == 0) {
            return;
        }

        String redirectUri = null;
        try {
            redirectUri = data.getQueryParameter("redirect_uri");
        } catch (Throwable ignored) {
        }

        // Only arm the bridge for Facebook Custom Tab style redirects.
        if (redirectUri == null || !redirectUri.startsWith("fbconnect://cct.")) {
            return;
        }

        saveSession(guestPackage, BActivityThread.getUserId());
        startKeepAlive();

        // Newer Facebook endpoints support an explicit package target for the redirect.
        // Point it at the host so Android does not directly launch a separately-installed
        // copy of the guest package. Older endpoints simply ignore this extra parameter.
        try {
            intent.setData(replaceQueryParameter(data, "intent_uri_package_target", BlackBoxCore.getHostPkg()));
        } catch (Throwable t) {
            Slog.w(TAG, "Unable to add callback package target: " + t.getMessage());
        }

        Slog.d(TAG, "Facebook callback bridge armed for guest=" + guestPackage
                + " redirect=" + redirectUri + " host=" + BlackBoxCore.getHostPkg());
    }

    private static Uri replaceQueryParameter(Uri source, String key, String replacement) {
        Uri.Builder builder = source.buildUpon().clearQuery();
        Set<String> names = source.getQueryParameterNames();
        boolean found = false;
        for (String name : names) {
            List<String> values = source.getQueryParameters(name);
            if (key.equals(name)) {
                builder.appendQueryParameter(name, replacement);
                found = true;
            } else if (values == null || values.isEmpty()) {
                builder.appendQueryParameter(name, null);
            } else {
                for (String value : values) {
                    builder.appendQueryParameter(name, value);
                }
            }
        }
        if (!found) {
            builder.appendQueryParameter(key, replacement);
        }
        return builder.build();
    }

    private static void saveSession(String guestPackage, int userId) {
        try {
            Context context = BlackBoxCore.getContext();
            if (context == null) return;
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_GUEST_PACKAGE, guestPackage)
                    .putInt(KEY_USER_ID, userId)
                    .apply();
        } catch (Throwable t) {
            Slog.w(TAG, "Unable to save Facebook callback session: " + t.getMessage());
        }
    }

    public static String getGuestPackage(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getString(KEY_GUEST_PACKAGE, null);
    }

    public static int getUserId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_USER_ID, 0);
    }

    public static void clearSession(Context context) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        } catch (Throwable ignored) {
        }
    }

    public static void startKeepAlive() {
        try {
            Context context = BlackBoxCore.getContext();
            if (context == null) return;
            Intent keepAlive = new Intent();
            keepAlive.setComponent(new ComponentName(
                    BlackBoxCore.getHostPkg(), FacebookLoginKeepAliveService.class.getName()));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(keepAlive);
            } else {
                context.startService(keepAlive);
            }
        } catch (Throwable t) {
            Slog.w(TAG, "Unable to start Facebook keep-alive: " + t.getMessage());
        }
    }

    public static void stopKeepAlive(Context context) {
        try {
            Intent keepAlive = new Intent();
            keepAlive.setAction(FacebookLoginKeepAliveService.ACTION_STOP);
            keepAlive.setComponent(new ComponentName(
                    context.getPackageName(), FacebookLoginKeepAliveService.class.getName()));
            // Use startService with a STOP action instead of stopService because the latter is
            // intentionally guarded by the virtual ActivityManager hook for cross-package calls.
            context.startService(keepAlive);
        } catch (Throwable ignored) {
        }
    }
}
