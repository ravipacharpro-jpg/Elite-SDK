package top.niunaijun.blackbox.fake.service;

import android.app.ActivityManager;
import android.content.Intent;
import android.os.IBinder;

import java.lang.reflect.Method;

import black.android.app.BRActivityTaskManager;
import black.android.app.BRIActivityTaskManagerStub;
import black.android.os.BRServiceManager;
import black.android.util.BRSingleton;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.compat.auth.ExternalAuthRouter;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.fake.hook.ProxyMethods;
import top.niunaijun.blackbox.fake.hook.ScanClass;
import top.niunaijun.blackbox.utils.MethodParameterUtils;
import top.niunaijun.blackbox.utils.compat.TaskDescriptionCompat;

@ScanClass(ActivityManagerCommonProxy.class)
public class IActivityTaskManagerProxy extends BinderInvocationStub {
    public static final String TAG = "ActivityTaskManager";

    public IActivityTaskManagerProxy() {
        super(BRServiceManager.get().getService("activity_task"));
    }

    @Override
    protected Object getWho() {
        return BRIActivityTaskManagerStub.get().asInterface(BRServiceManager.get().getService("activity_task"));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("activity_task");
        BRActivityTaskManager.get().getService();
        Object o = BRActivityTaskManager.get().IActivityTaskManagerSingleton();
        BRSingleton.get(o)._set_mInstance(BRIActivityTaskManagerStub.get().asInterface(this));
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("setTaskDescription")
    public static class SetTaskDescription extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ActivityManager.TaskDescription td = (ActivityManager.TaskDescription) args[1];
            args[1] = TaskDescriptionCompat.fix(td);
            return method.invoke(who, args);
        }
    }

    @ProxyMethods({
            "startActivityAsUser",
            "startActivityAsCaller",
            "startActivityWithConfig",
            "startActivityWithFeature"
    })
    public static class AuthStartActivityVariants extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (method == null || args == null) {
                return method == null ? 0 : method.invoke(who, args);
            }

            int intentIndex = findIntentIndex(method, args);
            if (intentIndex < 0) {
                return method.invoke(who, args);
            }

            Intent intent = (Intent) args[intentIndex];
            if (intent == null) {
                return method.invoke(who, args);
            }
            try {
                if (BActivityThread.getApplication() != null
                        && BActivityThread.getApplication().getClassLoader() != null) {
                    intent.setExtrasClassLoader(BActivityThread.getApplication().getClassLoader());
                }
            } catch (Throwable ignored) {
            }

            if (ExternalAuthRouter.isDirectProviderDispatch(intent)) {
                ExternalAuthRouter.clearDirectProviderDispatch(intent);
                if (ExternalAuthRouter.isTrustedProviderIntent(intent)) {
                    prepareHostLaunch(method, args);
                    return method.invoke(who, args);
                }
                return 0;
            }

            IBinder resultTo = findResultTo(method, args, intentIndex);
            int resultToIndex = findParameterIndexAfter(
                    method, args, intentIndex, IBinder.class);
            int resultWhoIndex = findStringParameterAfter(method, resultToIndex);
            String resultWho = valueAsString(args, resultWhoIndex);
            int requestCode = findIntValueAfter(method, args, resultWhoIndex);

            if (ExternalAuthRouter.isGoogleInteractiveSignInIntent(intent)) {
                ExternalAuthRouter.normalizeGoogleAuthIntentInGuest(intent);
            }

            Intent providerBridge = ExternalAuthRouter.createResultBridgeIntent(
                    intent,
                    resultTo,
                    resultWho,
                    requestCode,
                    BActivityThread.getAppPackageName());
            if (providerBridge != null) {
                args[intentIndex] = providerBridge;
                prepareHostLaunch(method, args);
                return method.invoke(who, args);
            }

            return new ActivityManagerCommonProxy.StartActivity().hook(who, method, args);
        }

        private static void prepareHostLaunch(Method method, Object[] args) {
            MethodParameterUtils.replaceFirstAppPkg(args);
            String name = method == null ? "" : method.getName();
            if (name.contains("AsUser")
                    || name.contains("AsCaller")
                    || name.contains("WithConfig")) {
                MethodParameterUtils.replaceLastUserId(args);
            }
        }

        private static int findIntentIndex(Method method, Object[] args) {
            if (method != null) {
                Class<?>[] types = method.getParameterTypes();
                int count = Math.min(types.length, args.length);
                for (int i = 0; i < count; i++) {
                    if (Intent.class.isAssignableFrom(types[i]) && args[i] instanceof Intent) {
                        return i;
                    }
                }
            }
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Intent) {
                    return i;
                }
            }
            return -1;
        }

        private static IBinder findResultTo(Method method, Object[] args, int intentIndex) {
            int index = findParameterIndexAfter(method, args, intentIndex, IBinder.class);
            return index >= 0 && args[index] instanceof IBinder ? (IBinder) args[index] : null;
        }

        private static int findParameterIndexAfter(
                Method method, Object[] args, int afterIndex, Class<?> expectedType) {
            if (method == null || args == null || expectedType == null) {
                return -1;
            }
            Class<?>[] types = method.getParameterTypes();
            int count = Math.min(types.length, args.length);
            for (int i = Math.max(0, afterIndex + 1); i < count; i++) {
                if (expectedType.isAssignableFrom(types[i]) && expectedType.isInstance(args[i])) {
                    return i;
                }
            }
            for (int i = Math.max(0, afterIndex + 1); i < args.length; i++) {
                if (expectedType.isInstance(args[i])) {
                    return i;
                }
            }
            return -1;
        }

        private static int findStringParameterAfter(Method method, int afterIndex) {
            if (method == null) {
                return -1;
            }
            Class<?>[] types = method.getParameterTypes();
            for (int i = Math.max(0, afterIndex + 1); i < types.length; i++) {
                if (String.class.isAssignableFrom(types[i])) {
                    return i;
                }
            }
            return -1;
        }

        private static int findIntValueAfter(Method method, Object[] args, int afterIndex) {
            if (method == null || args == null) {
                return -1;
            }
            Class<?>[] types = method.getParameterTypes();
            int count = Math.min(types.length, args.length);
            for (int i = Math.max(0, afterIndex + 1); i < count; i++) {
                if (types[i] == int.class || types[i] == Integer.class) {
                    Object val = args[i];
                    return val instanceof Integer ? (Integer) val : -1;
                }
            }
            return -1;
        }

        private static String valueAsString(Object[] args, int index) {
            if (args == null || index < 0 || index >= args.length) {
                return null;
            }
            Object val = args[index];
            return val instanceof String ? (String) val : null;
        }
    }
}
