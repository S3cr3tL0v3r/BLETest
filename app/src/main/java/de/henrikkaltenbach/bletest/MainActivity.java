package de.henrikkaltenbach.bletest;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final int ENABLE_BLUETOOTH_REQUEST_CODE = 1;
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 2;
    private static final int REQUEST_CODE_FOR_OVERLAY_SCREEN = 106;
    private static final long SCAN_PERIOD = 10000;
    private static final String ENVIRONMENTAL_SENSING_SERVICE_UUID = "0000181A-0000-1000-8000-00805F9B34FB";
    private static final String TEMPERATURE_CHARACTERISTIC_UUID = "00002A6E-0000-1000-8000-00805F9B34FB";
    private static final String CCC_DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805F9B34FB";
    private static final String DEVICE_NAME = "Henrik's ESP32";


    private Toolbar toolbar;
    private ProgressBar progressCircle;
    private TextView tvDevice;
    private TextView tvTemperature;
    private TextView tvCelsius;
    private BluetoothLeScanner bleScanner;
    private ScanFilter scanFilter;
    private ScanSettings scanSettings;
    private Handler handler;
    private boolean isScanning;
    private boolean isDisconnected;
    private Context context;
    private BluetoothGatt bleGatt;

    private Button bWidget;
    private GetFloatingIconClick receiver;
    private IntentFilter filter;

    private BluetoothAdapter getBluetoothAdapter() {
        return ((BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            stopBleScan();
            result.getDevice().connectGatt(context, false, gattCallback);
            BluetoothDevice bleDevice = result.getDevice();
            Log.i( "ScanCallback", String.format("Connect to %s (%s)", bleDevice.getName(), bleDevice.getAddress()));
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e("ScanCallback", "onScanFailed: code " + errorCode);
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            BluetoothDevice bleDevice = gatt.getDevice();
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                bleGatt = gatt;
                runOnUiThread(() -> {
                    tvDevice.setText(bleDevice.getName());
                    toolbar.setBackgroundColor(Color.GREEN);
                    tvTemperature.setVisibility(View.VISIBLE);
                    tvCelsius.setVisibility(View.VISIBLE);
                });
                isDisconnected = false;
                Log.i("BluetoothGattCallback", String.format("Successfully connected to %s (%s)", bleDevice.getName(), bleDevice.getAddress()));
                handler.post(() -> bleGatt.discoverServices());
                Log.i("BluetoothGattCallback", String.format("Discover services on %s (%s)", bleDevice.getName(), bleDevice.getAddress()));
            } else  {
                gatt.close();
                runOnUiThread(() -> {
                    tvDevice.setText(R.string.no_device);
                    toolbar.setBackgroundResource(R.color.teal_700);
                    tvTemperature.setVisibility(View.INVISIBLE);
                    tvCelsius.setVisibility(View.INVISIBLE);
                });
                isDisconnected = true;
                startBleScan();
                Log.e("BluetoothGattCallback",  String.format("Successfully disconnected from or connection error with %s (%s)", bleDevice.getName(), bleDevice.getAddress()));
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            bleGatt = gatt;
            BluetoothGattCharacteristic characteristic = bleGatt
                    .getService(UUID.fromString(ENVIRONMENTAL_SENSING_SERVICE_UUID))
                    .getCharacteristic(UUID.fromString(TEMPERATURE_CHARACTERISTIC_UUID));
            UUID cccdUUID = UUID.fromString(CCC_DESCRIPTOR_UUID);
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(cccdUUID);
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (bleGatt.setCharacteristicNotification(characteristic, true)) {
                bleGatt.writeDescriptor(descriptor);
                Log.i("BluetoothGattCallback", "Successfully subscribed to characteristic notifications");
            } else {
                Log.e("BluetoothGattCallback", "Could not subscribe to characteristic notifications");
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            double value = ByteBuffer.wrap(characteristic.getValue()).order(ByteOrder.LITTLE_ENDIAN).getDouble();
            String oilTemperature = String.format(Locale.US, "%4.1f", value);
            runOnUiThread(()-> tvTemperature.setText(oilTemperature));
            Log.i("BluetoothGattCallback", "Characteristic value changed. Oil temperature: " + oilTemperature);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        context = this;
        toolbar = findViewById(R.id.toolbar);
        progressCircle =  findViewById(R.id.progressCircle);
        tvDevice = findViewById(R.id.tvDevice);
        tvDevice.setOnClickListener(view -> {
            if (isDisconnected) {
                startBleScan();
            }
        });
        tvTemperature = findViewById(R.id.tvTemperature);
        tvCelsius = findViewById(R.id.tvCelsius);
        scanFilter = new ScanFilter.Builder()
                .setDeviceName(DEVICE_NAME)
                .setServiceUuid(ParcelUuid.fromString(ENVIRONMENTAL_SENSING_SERVICE_UUID))
                .build();
        scanSettings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
                .build();
        handler = new Handler(Looper.getMainLooper());
        isDisconnected = true;
        bWidget = findViewById(R.id.bWidget);
        bWidget.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    if (!Settings.canDrawOverlays(MainActivity.this)) {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivityForResult(intent, REQUEST_CODE_FOR_OVERLAY_SCREEN);
                    } else {
                        initializeView();
                    }
                } catch (ActivityNotFoundException ignored) {
                }
            }
        });
        filter = new IntentFilter();
    }

    @Override
    protected void onResume() {
        super.onResume();
        receiver = new GetFloatingIconClick();
        filter.addAction(FloatingWidgetService.BROADCAST_ACTION);
        registerReceiver(receiver, filter);
        if (!getBluetoothAdapter().isEnabled()) {
            promptEnableBluetooth();
        } else if (isDisconnected) {
            bleScanner = getBluetoothAdapter().getBluetoothLeScanner();
            startBleScan();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isScanning) {
            stopBleScan();
        }
    }

    private void startBleScan() {
        if (isLocationPermissionDenied()) {
            requestLocationPermission();
        } else if (!isScanning) {
            bleScanner.startScan(Collections.singletonList(scanFilter), scanSettings, scanCallback);
            isScanning = true;
            runOnUiThread(()-> progressCircle.setVisibility(View.VISIBLE));
            handler.postDelayed(this::stopBleScan, SCAN_PERIOD);
            Log.i("BluetoothLeScanner", "Scan started");
        }
    }

    private void stopBleScan() {
        if (isScanning) {
            bleScanner.stopScan(scanCallback);
            isScanning = false;
            runOnUiThread(()-> progressCircle.setVisibility(View.INVISIBLE));
            Log.i("BluetoothLeScanner", "Scan stopped");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ENABLE_BLUETOOTH_REQUEST_CODE && resultCode != Activity.RESULT_OK) {
            promptEnableBluetooth();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startBleScan();
        } else {
            requestLocationPermission();
        }
    }

    private void promptEnableBluetooth() {
        if (!getBluetoothAdapter().isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, ENABLE_BLUETOOTH_REQUEST_CODE);
        }
    }

    private void requestLocationPermission() {
        if (isLocationPermissionDenied()) {
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle("Location permission required")
                    .setMessage("Starting from Android M (6.0), the system requires apps to be granted location access in order to scan for BLE devices.")
                    .setCancelable(false)
                    .setPositiveButton("Ok", (dialogInterface, i) -> requestPermission(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            LOCATION_PERMISSION_REQUEST_CODE
                    ))
                    .show());
        }
    }

    private boolean isLocationPermissionDenied() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermission(String permission, int requestCode) {
        ActivityCompat.requestPermissions(this, new String[]{permission}, requestCode);
    }

    private void initializeView() {
        Intent startIntent = new Intent(MainActivity.this, FloatingWidgetService.class);
        startService(startIntent);
    }

    private class GetFloatingIconClick extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Intent selfIntent = new Intent(MainActivity.this, MainActivity.class);
            selfIntent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(selfIntent);
        }
    }
}