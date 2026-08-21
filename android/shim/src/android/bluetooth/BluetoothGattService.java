package android.bluetooth;

/**
 * Compile-only shim for {@code android.bluetooth.BluetoothGattService}.
 * The environment's reduced android.jar has a different
 * {@code getCharacteristic} / {@code getUuid} shape (it is keyed off
 * {@code java.util.UUID} rather than the *real* {@code BluetoothUuid}), so
 * we supply the *real* API shape (signatures only — the bodies are never
 * executed; at runtime on a real device the framework's own class is used,
 * and the shim is kept off the packaged classpath via {@code compileOnly}).
 */
public class BluetoothGattService {
    public BluetoothUuid getUuid() { return null; }
    public BluetoothGattCharacteristic getCharacteristic(BluetoothUuid uuid) { return null; }
}
