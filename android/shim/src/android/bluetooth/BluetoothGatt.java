package android.bluetooth;

import java.util.List;

/**
 * Compile-only shim for {@code android.bluetooth.BluetoothGatt}. The
 * environment's reduced android.jar lacks {@code cancelIfStarted()} and the
 * other members this app uses, so we supply the *real* API shape
 * (signatures only — the bodies are never executed; at runtime on a real
 * device the framework's own class is used).
 */
public class BluetoothGatt {
    public void cancelIfStarted() { }
    public void close() { }
    public void requestMtu(int mtu) { }
    public void discoverServices() { }
    public boolean setCharacteristicNotification(BluetoothGattCharacteristic c, boolean notify) { return false; }
    public boolean writeDescriptor(BluetoothGattDescriptor d) { return false; }
    public boolean writeCharacteristic(BluetoothGattCharacteristic c) { return false; }
    public List<BluetoothGattService> getServices() { return null; }
    public BluetoothGattService getService(java.util.UUID uuid) { return null; }
}
