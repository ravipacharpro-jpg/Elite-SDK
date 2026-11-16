package top.niunaijun.blackbox.fake.hook;

import android.text.TextUtils;
import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.lang.reflect.InvocationTargetException;
import top.niunaijun.blackbox.utils.MethodParameterUtils;


public abstract class ClassInvocationStub implements InvocationHandler, IInjectHook {
    public static final String TAG = ClassInvocationStub.class.getSimpleName();

    private final Map<String, MethodHook> mMethodHookMap = new HashMap<>();
    private Object mBase;
    private Object mProxyInvocation;
    private boolean onlyProxy;

    protected abstract Object getWho();

    protected abstract void inject(Object baseInvocation, Object proxyInvocation);

    protected void onBindMethod() {

    }

    protected Object getProxyInvocation() {
        return mProxyInvocation;
    }

    protected Object getBase() {
        return mBase;
    }

    protected void onlyProxy(boolean o) {
        onlyProxy = o;
    }

    @Override
    public void injectHook() {
        mBase = getWho();
        
        if (mBase == null) {
            return;
        }
        mProxyInvocation = Proxy.newProxyInstance(mBase.getClass().getClassLoader(), MethodParameterUtils.getAllInterface(mBase.getClass()), this);
        if (!onlyProxy) {
            inject(mBase, mProxyInvocation);
        }

        onBindMethod();
        Class<?>[] declaredClasses = this.getClass().getDeclaredClasses();
        for (Class<?> declaredClass : declaredClasses) {
            initAnnotation(declaredClass);
        }
        ScanClass scanClass = this.getClass().getAnnotation(ScanClass.class);
        if (scanClass != null) {
            for (Class<?> aClass : scanClass.value()) {
                for (Class<?> declaredClass : aClass.getDeclaredClasses()) {
                    initAnnotation(declaredClass);
                }
            }
        }
    }

    protected void initAnnotation(Class<?> clazz) {
        ProxyMethod proxyMethod = clazz.getAnnotation(ProxyMethod.class);
        if (proxyMethod != null) {
            final String name = proxyMethod.value();
            if (!TextUtils.isEmpty(name)) {
                try {
                    addMethodHook(name, (MethodHook) clazz.newInstance());
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        }
        ProxyMethods proxyMethods = clazz.getAnnotation(ProxyMethods.class);
        if (proxyMethods != null) {
            String[] value = proxyMethods.value();
            for (String name : value) {
                try {
                    addMethodHook(name, (MethodHook) clazz.newInstance());
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        }
    }

    protected void addMethodHook(MethodHook methodHook) {
        mMethodHookMap.put(methodHook.getMethodName(), methodHook);
    }

    protected void addMethodHook(String name, MethodHook methodHook) {
        mMethodHookMap.put(name, methodHook);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {











                // AttributionSource aur dynamic state system UID rewrite logic
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] != null && args[i].getClass().getName().contains("AttributionSource")) {
                    try {
                        java.lang.reflect.Field uidField = args[i].getClass().getDeclaredField("mAttributionSourceState");
                        uidField.setAccessible(true);
                        Object state = uidField.get(args[i]);
                        if (state != null) {
                            java.lang.reflect.Field stateUid = state.getClass().getDeclaredField("uid");
                            stateUid.setAccessible(true);
                            stateUid.setInt(state, android.os.Process.myUid());
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        MethodHook methodHook = mMethodHookMap.get(method.getName());
        if (methodHook == null || !methodHook.isEnable()) {
            try {
                return method.invoke(mBase, args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                // Never fake a successful call by returning Integer 0 here:
                // many Android service methods return Bundle, String, etc.
                // The wrong type caused a ClassCastException in WebView.
                if (cause instanceof SecurityException) {
                    Log.w(TAG, "Permission denied by " + method.getName()
                            + " (return type: " + method.getReturnType().getName() + ")", cause);
                }
                throw cause != null ? cause : e;
            } catch (Throwable e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
        }
      

        Object result = methodHook.beforeHook(mBase, method, args);
        if (result != null) {
            return result;
        }
        result = methodHook.hook(mBase, method, args);
        result = methodHook.afterHook(result);
        return result;
    }
}
