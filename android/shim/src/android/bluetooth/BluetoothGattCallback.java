package android.bluetooth;

/**
 * Compile-only shim for {@code android.bluetooth.BluetoothGattCallback}.
 * The environment's reduced android.jar has the *wrong* signatures (first
 * param is {@code BluetoothGatt} instead of {@code BluetoothDevice}), so we
 * supply the *real* API shape (signatures only — the bodies are never
 * executed; at runtime on a real device the framework's own class is used,
 * and the shim is kept off the packaged classpath via {@code compileOnly}).
 */
public abstract class BluetoothGattCallback {
    public void onConnectionStateChange(BluetoothDevice device, int status, int newState) { }
    public void onServicesDiscovered(BluetoothDevice device, int status) { }
    public void onCharacteristicChanged(BluetoothDevice device, int status, BluetoothGattCharacteristic characteristic) { }
    public void onMtuChanged(BluetoothDevice device, int mtu) { }
    public void onReadRemoteRssi(BluetoothDevice device, int status, short rssi) { }
    public void onReliableWriteCompleted(BluetoothDevice device, int status) { }
    public void onPhyUpdate(BluetoothDevice device, int status, int txPhy, int rxPhy) { }
    public void onPhyRead(BluetoothDevice device, int status, int txPhy, int rxPhy) { }
    public void onCharacteristicRead(BluetoothDevice device, int status, BluetoothGattCharacteristic characteristic) { }
    public void onCharacteristicWrite(BluetoothDevice device, int status, BluetoothGattCharacteristic characteristic) { }
    public void onDescriptorRead(BluetoothDevice device, int status, BluetoothGattDescriptor descriptor) { }
    public void onDescriptorWrite(BluetoothDevice device, int status, BluetoothGattDescriptor descriptor) { }
    public void onServiceChanged(BluetoothDevice device) { }
}
