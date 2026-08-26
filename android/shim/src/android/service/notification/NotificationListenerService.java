package android.service.notification;

import android.app.Service;

/**
 * Compile-only shim for {@code android.service.notification.NotificationListenerService}.
 * The environment's reduced android.jar lacks the {@code onNotificationStatusChanged}
 * override point this app needs, so we supply the *real* API shape
 * (signatures only — the bodies are never executed; at runtime on a real
 * device the framework's own class is used, and the shim is kept off the
 * packaged classpath via {@code compileOnly}).
 */
public class NotificationListenerService extends Service {
    public void onNotificationStatusChanged(StatusBarNotification pn) { }
    public void onNotificationRemoved(StatusBarNotification pn) { }

    // Service's single abstract method — the real framework provides the
    // real binder; this shim body is never executed (compile-only).
    public android.os.IBinder onBind(android.content.Intent intent) { return null; }
}
