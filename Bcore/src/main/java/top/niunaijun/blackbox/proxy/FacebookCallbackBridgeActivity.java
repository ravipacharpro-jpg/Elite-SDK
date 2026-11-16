package top.niunaijun.blackbox.proxy;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.utils.FacebookLoginCompat;
import top.niunaijun.blackbox.utils.Slog;

/** Receives the browser's Facebook CCT callback and relays it to the virtual guest. */
public class FacebookCallbackBridgeActivity extends Activity {
    private static final String TAG = "FacebookCallbackBridge";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        relay(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        relay(intent);
    }

    private void relay(Intent source) {
        final Uri callback = source != null ? source.getData() : null;
        final String guestPackage = FacebookLoginCompat.getGuestPackage(this);
        final int userId = FacebookLoginCompat.getUserId(this);

        if (callback == null || guestPackage == null || guestPackage.length() == 0) {
            Slog.w(TAG, "Missing callback URI or guest mapping; cannot relay");
            FacebookLoginCompat.stopKeepAlive(this);
            finish();
            return;
        }

        Uri guestUri = callback;
        if ("fbconnect".equalsIgnoreCase(callback.getScheme())) {
            String expectedAuthority = "cct." + guestPackage;
            if (!expectedAuthority.equalsIgnoreCase(callback.getAuthority())) {
                guestUri = callback.buildUpon().authority(expectedAuthority).build();
            }
        }

        final Intent target = new Intent(Intent.ACTION_VIEW, guestUri);
        target.addCategory(Intent.CATEGORY_DEFAULT);
        target.addCategory(Intent.CATEGORY_BROWSABLE);
        target.setPackage(guestPackage);
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        Slog.d(TAG, "Relaying Facebook callback to virtual guest=" + guestPackage);

        // Give the :black process a brief moment to settle if the OEM restarted it.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                BlackBoxCore.getBActivityManager().startActivity(target, userId);
            } catch (Throwable first) {
                Slog.w(TAG, "Direct callback relay failed, retrying: " + first.getMessage());
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        BlackBoxCore.getBActivityManager().startActivity(target, userId);
                    } catch (Throwable second) {
                        Slog.e(TAG, "Facebook callback relay failed", second);
                    } finally {
                        finishBridge();
                    }
                }, 350L);
                return;
            }
            finishBridge();
        }, 150L);
    }

    private void finishBridge() {
        FacebookLoginCompat.stopKeepAlive(this);
        FacebookLoginCompat.clearSession(this);
        finish();
    }
}
