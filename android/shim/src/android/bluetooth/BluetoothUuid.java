package android.bluetooth;

/**
 * Compile-only shim. The environment's android.jar is a reduced/modified
 * platform that is missing this class, so we supply the *real* API shape
 * (signatures only — the bodies are never executed; at runtime on a real
 * device the framework's own class is used).
 */
public final class BluetoothUuid {
    public static final BluetoothUuid CCCD =
        new BluetoothUuid("00002902-0000-1000-8000-00805F9B34FB");

    public static BluetoothUuid fromString(String s) { return null; }
    public static BluetoothUuid fromLong(long uuid) { return null; }

    public boolean equals(Object o) { return false; }
    public int hashCode() { return 0; }
    public String toString() { return ""; }

    private BluetoothUuid(String s) { }
}
