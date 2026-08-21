package android.content;

/**
 * Compile-only shim for {@code android.content.Extras}. The environment's
 * reduced android.jar is missing this class entirely, so we supply the
 * *real* API shape (signatures only — the bodies are never executed; at
 * runtime on a real device the framework's own class is used, and the shim
 * is kept off the packaged classpath via {@code compileOnly}).
 */
public final class Extras {
    public CharSequence getCharSequence(String name) { return null; }
    public String getString(String name) { return null; }
    public int getInt(String name) { return 0; }
    public long getLong(String name) { return 0L; }
    public boolean getBoolean(String name) { return false; }
}
