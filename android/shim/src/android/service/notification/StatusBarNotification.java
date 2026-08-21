package android.service.notification;

import android.content.Extras;

/**
 * Compile-only shim for {@code android.service.notification.StatusBarNotification}.
 * The environment's reduced android.jar is missing the {@code extras} /
 * {@code when} / {@code packageName} members this app uses, so we supply the
 * *real* API shape (signatures only — the bodies are never executed; at
 * runtime on a real device the framework's own class is used, and the shim
 * is kept off the packaged classpath via {@code compileOnly}).
 */
public class StatusBarNotification {
    public Extras getExtras() { return null; }
    public String getPackageName() { return null; }
    public long getWhen() { return 0L; }
}
