package top.niunaijun.blackbox.compat.auth;

import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.compat.oauth.VirtualOAuthBridgeActivity;

/**
 * Routes native sign-in activities and provider-owned IntentSenders to the host bridge
 * while keeping the activity-result target inside the virtual process.
 */
public final class ExternalAuthRouter {
    public static final String EXTRA_EXTERNAL_AUTH =
            "top.niunaijun.blackbox.auth.EXTERNAL_AUTH";
    public static final String EXTRA_GOOGLE_WEB_AUTH =
            "top.niunaijun.blackbox.auth.GOOGLE_WEB_AUTH";
    public static final String EXTRA_GOOGLE_SERVER_CLIENT_ID =
            "top.niunaijun.blackbox.auth.GOOGLE_SERVER_CLIENT_ID";
    public static final String EXTRA_BROWSER_AUTH =
            "top.niunaijun.blackbox.auth.BROWSER_AUTH";
    public static final String EXTRA_PROVIDER_INTENT =
            "top.niunaijun.blackbox.auth.PROVIDER_INTENT";
    public static final String EXTRA_PROVIDER_INTENT_SENDER =
            "top.niunaijun.blackbox.auth.PROVIDER_INTENT_SENDER";
    public static final String EXTRA_PROVIDER_FILL_IN_INTENT =
            "top.niunaijun.blackbox.auth.PROVIDER_FILL_IN_INTENT";
    public static final String EXTRA_PROVIDER_FLAGS_MASK =
            "top.niunaijun.blackbox.auth.PROVIDER_FLAGS_MASK";
    public static final String EXTRA_PROVIDER_FLAGS_VALUES =
            "top.niunaijun.blackbox.auth.PROVIDER_FLAGS_VALUES";
    public static final String EXTRA_PROVIDER_OPTIONS =
            "top.niunaijun.blackbox.auth.PROVIDER_OPTIONS";
    public static final String EXTRA_RESULT_BINDER =
            "top.niunaijun.blackbox.auth.RESULT_BINDER";
    public static final String EXTRA_RESULT_WHO =
            "top.niunaijun.blackbox.auth.RESULT_WHO";
    public static final String EXTRA_REQUEST_CODE =
            "top.niunaijun.blackbox.auth.REQUEST_CODE";
    public static final String EXTRA_VIRTUAL_PACKAGE =
            "top.niunaijun.blackbox.auth.VIRTUAL_PACKAGE";
    public static final String EXTRA_DIRECT_PROVIDER_DISPATCH =
            "top.niunaijun.blackbox.auth.DIRECT_PROVIDER_DISPATCH";
    public static final String EXTRA_BPID =
            "top.niunaijun.blackbox.auth.BPID";
    public static final String EXTRA_USER_ID =
            "top.niunaijun.blackbox.auth.USER_ID";
    public static final String EXTRA_RESULT_CODE =
            "top.niunaijun.blackbox.auth.RESULT_CODE";
    public static final String EXTRA_RESULT_DATA =
            "top.niunaijun.blackbox.auth.RESULT_DATA";
    public static final String EXTRA_RESULT_DELIVERED =
            "top.niunaijun.blackbox.auth.RESULT_DELIVERED";
    public static final String EXTRA_MANUAL_RESULT_RELAY =
            "top.niunaijun.blackbox.auth.MANUAL_RESULT_RELAY";

    public static final String METHOD_DELIVER_ACTIVITY_RESULT =
            "_Black_|_auth_activity_result_";

    private static final Set<String> TRUSTED_PROVIDER_PACKAGES = new HashSet<>(Arrays.asList(
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.gsf.login",
            "com.android.vending",
            "com.google.android.play.games",
            "com.android.credentialmanager",
            "com.google.android.credentialmanager",
            "com.google.android.apps.authenticator2",
            "com.instagram.android",
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.discord",
            "com.vkontakte.android",
            "jp.naver.line.android",
            "org.telegram.messenger",
            "com.tencent.mm",
            "com.tencent.mobileqq"
    ));

    private static final Set<String> GOOGLE_DIRECT_AUTH_PACKAGES = new HashSet<>(Arrays.asList(
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.gsf.login",
            "com.android.vending",
            "com.google.android.play.games",
            "com.android.credentialmanager",
            "com.google.android.credentialmanager",
            "com.google.android.apps.authenticator2"
    ));

    public static boolean isGoogleInteractiveSignInIntent(Intent intent) {
        if (intent == null) return false;
        ComponentName comp = intent.getComponent();
        if (comp != null && comp.getClassName() != null
                && comp.getClassName().endsWith("SignInHubActivity")) {
            return false;
        }
        String pkg = comp != null ? comp.getPackageName() : intent.getPackage();
        if (!"com.google.android.gms".equals(pkg)) {
            String action = intent.getAction();
            if (action != null) {
                String lowerAct = action.toLowerCase(Locale.US);
                if (lowerAct.contains("google_sign_in") || lowerAct.contains("gms.auth.api.signin")) {
                    return true;
                }
            }
            return false;
        }
        String action = intent.getAction();
        if (action != null) {
            String lowerAct = action.toLowerCase(Locale.US);
            if (lowerAct.contains("google_sign_in")
                    || lowerAct.contains("gms.auth.api.signin")
                    || lowerAct.contains("choose_account")
                    || lowerAct.contains("gms.games.sign_in")) {
                return true;
            }
        }
        if (comp != null && comp.getClassName() != null) {
            String lowerCls = comp.getClassName().toLowerCase(Locale.US);
            if (lowerCls.contains("signin") || lowerCls.contains("accountpicker") || lowerCls.contains("chooseaccount")) {
                return true;
            }
        }
        return false;
    }

    public static boolean isDirectProviderDispatch(Intent intent) {
        return intent != null && intent.getBooleanExtra(EXTRA_DIRECT_PROVIDER_DISPATCH, false);
    }

    public static void clearDirectProviderDispatch(Intent intent) {
        if (intent != null) {
            intent.removeExtra(EXTRA_DIRECT_PROVIDER_DISPATCH);
        }
    }

    public static boolean isTrustedProviderIntent(Intent intent) {
        return getTrustedProviderPackage(intent) != null;
    }

    public static boolean isGoogleDirectAuthPackage(String pkg) {
        return pkg != null && GOOGLE_DIRECT_AUTH_PACKAGES.contains(pkg);
    }

    public static void normalizeGoogleAuthIntentInGuest(Intent intent) {
        if (intent == null) return;
        String virtualPkg = BActivityThread.getAppPackageName();
        String hostPkg = BlackBoxCore.getHostPkg();
        if (virtualPkg == null || hostPkg == null || virtualPkg.equals(hostPkg)) {
            return;
        }
        try {
            if (BActivityThread.getApplication() != null
                    && BActivityThread.getApplication().getClassLoader() != null) {
                intent.setExtrasClassLoader(BActivityThread.getApplication().getClassLoader());
            }
            if (intent.hasExtra("config")) {
                GoogleSignInTokenHelper.prepareOutboundSignInIntent(intent, virtualPkg, hostPkg);
            }
            String[] pkgKeys = new String[]{
                    "callingPackage",
                    "authPackage",
                    "clientPackageName",
                    "androidPackageName",
                    "package_name",
                    "com.google.android.gms.signin.service.realClientPackageName",
                    "com.google.android.gms.games.key.gamePackageName",
                    "com.google.android.gms.common.account.CHOOSE_ACCOUNT_PACKAGE"
            };
            for (String key : pkgKeys) {
                if (intent.hasExtra(key)) {
                    String val = intent.getStringExtra(key);
                    if (virtualPkg.equals(val)) {
                        intent.putExtra(key, hostPkg);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private ExternalAuthRouter() {
    }

    public static boolean isTrustedProviderPackage(String packageName) {
        return packageName != null && TRUSTED_PROVIDER_PACKAGES.contains(packageName);
    }

    public static String getTrustedProviderPackage(Intent intent) {
        if (intent == null) return null;
        ComponentName comp = intent.getComponent();
        String pkg = comp != null ? comp.getPackageName() : intent.getPackage();
        return isTrustedProviderPackage(pkg) ? pkg : null;
    }

    public static Intent createResultBridgeIntent(
            Intent source,
            IBinder resultTo,
            String resultWho,
            int requestCode,
            String virtualPackage) {
        if (source == null || resultTo == null || requestCode < 0
                || virtualPackage == null || virtualPackage.trim().isEmpty()) {
            return null;
        }

        // Route Google Sign-In directly to our Native Account Picker inside VirtualOAuthBridgeActivity
        if (isGoogleInteractiveSignInIntent(source)) {
            normalizeGoogleAuthIntentInGuest(source);
            int bpid = BActivityThread.getAppPid();
            if (bpid >= 0 && bpid <= 50) {
                Intent googleWebBridge = createBaseBridge(
                        resultTo, resultWho, requestCode, virtualPackage, bpid);
                googleWebBridge.putExtra(EXTRA_EXTERNAL_AUTH, false);
                googleWebBridge.putExtra(EXTRA_GOOGLE_WEB_AUTH, true);
                String serverClientId = GoogleSignInTokenHelper.getLastServerClientId();
                if (serverClientId != null) {
                    googleWebBridge.putExtra(EXTRA_GOOGLE_SERVER_CLIENT_ID, serverClientId);
                }
                return prepareBridgeForLaunch(googleWebBridge);
            }
        }

        String providerPackage = getTrustedProviderPackage(source);
        if (providerPackage == null) {
            return null;
        }
        if (isGoogleDirectAuthPackage(providerPackage)) {
            if (source.getComponent() == null && source.getPackage() == null) {
                source.setPackage(providerPackage);
            }
            normalizeGoogleAuthIntentInGuest(source);
            return null;
        }

        int bpid = BActivityThread.getAppPid();
        if (bpid < 0 || bpid > 50) {
            return null;
        }

        Intent providerIntent = new Intent(source);
        if (providerIntent.getComponent() == null && providerIntent.getPackage() == null) {
            providerIntent.setPackage(providerPackage);
        }
        providerIntent.putExtra(EXTRA_DIRECT_PROVIDER_DISPATCH, true);

        Intent bridge = createBaseBridge(
                resultTo, resultWho, requestCode, virtualPackage, bpid);
        bridge.putExtra(EXTRA_PROVIDER_INTENT, providerIntent);
        bridge.addFlags(source.getFlags() & (
                Intent.FLAG_ACTIVITY_NO_ANIMATION
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        return prepareBridgeForLaunch(bridge);
    }

    private static Intent prepareBridgeForLaunch(Intent bridge) {
        top.niunaijun.blackbox.utils.compat.IntentRedirectCompat.collectNestedIntentKeys(bridge);
        return bridge;
    }

    private static Intent createBaseBridge(
            IBinder resultTo,
            String resultWho,
            int requestCode,
            String virtualPackage,
            int bpid) {
        Intent bridge = new Intent();
        bridge.setComponent(new ComponentName(
                BlackBoxCore.getHostPkg(),
                VirtualOAuthBridgeActivity.class.getName()));
        bridge.putExtra(EXTRA_EXTERNAL_AUTH, true);
        bridge.putExtra(EXTRA_BPID, bpid);
        bridge.putExtra(EXTRA_USER_ID, BActivityThread.getUserId());
        putResultTarget(bridge, resultTo, resultWho, requestCode, virtualPackage);
        return bridge;
    }

    private static void putResultTarget(
            Intent bridge,
            IBinder resultTo,
            String resultWho,
            int requestCode,
            String virtualPackage) {
        bridge.putExtra(EXTRA_RESULT_WHO, resultWho);
        bridge.putExtra(EXTRA_REQUEST_CODE, requestCode);
        bridge.putExtra(EXTRA_VIRTUAL_PACKAGE, virtualPackage);
        Bundle binderBundle = new Bundle();
        binderBundle.putBinder(EXTRA_RESULT_BINDER, resultTo);
        bridge.putExtras(binderBundle);
    }
}
