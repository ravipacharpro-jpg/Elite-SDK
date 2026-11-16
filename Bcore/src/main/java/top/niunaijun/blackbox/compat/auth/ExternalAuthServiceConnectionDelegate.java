package top.niunaijun.blackbox.compat.auth;

import android.app.IServiceConnection;
import android.content.ComponentName;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import top.niunaijun.blackbox.fake.delegate.ServiceConnectionDelegate;

/**
 * Keeps external authentication service callbacks inside the virtual process
 * while allowing the actual provider service on the phone to remain authoritative.
 */
public final class ExternalAuthServiceConnectionDelegate extends IServiceConnection.Stub {
    private static final Map<IBinder, ExternalAuthServiceConnectionDelegate> CACHE =
            new ConcurrentHashMap<>();

    private final IServiceConnection base;

    private ExternalAuthServiceConnectionDelegate(IServiceConnection base) {
        this.base = base;
    }

    public static IServiceConnection createProxy(IServiceConnection base) {
        if (base == null) {
            return null;
        }
        final IBinder binder = base.asBinder();
        ExternalAuthServiceConnectionDelegate cached = CACHE.get(binder);
        if (cached != null) {
            return cached;
        }

        ExternalAuthServiceConnectionDelegate created =
                new ExternalAuthServiceConnectionDelegate(base);
        ExternalAuthServiceConnectionDelegate previous = CACHE.putIfAbsent(binder, created);
        ExternalAuthServiceConnectionDelegate result = previous != null ? previous : created;

        if (previous == null) {
            try {
                binder.linkToDeath(() -> CACHE.remove(binder), 0);
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    @Override
    public void connected(ComponentName name, IBinder service) throws RemoteException {
        connected(name, service, false);
    }

    public void connected(ComponentName name, IBinder service, boolean dead)
            throws RemoteException {
        connected(name, service, null, dead);
    }

    public void connected(ComponentName name, IBinder service, IBinder session, boolean dead)
            throws RemoteException {
        IBinder delivered = GmsBrokerCompat.wrap(service);
        ServiceConnectionDelegate.dispatchConnected(base, name, delivered, session, dead);
    }
}
