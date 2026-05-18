package com.zoro.ugv;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

public class MainActivity extends AppCompatActivity {

    private static final String ESP32_MAC = "EC:94:CB:4A:6E:9E";
    private static final int    PERM_CODE = 101;

    private static BluetoothGatt gatt;
    public static BluetoothGatt getGatt() { return gatt; }

    private VoiceCommandManager vcm;
    private TemplateStore       store;
    private TextView            statusText;
    private Button              speakButton;
    private final Handler       reconnectHandler = new Handler(Looper.getMainLooper());
    private boolean             isConnecting = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText  = findViewById(R.id.statusText);
        speakButton = findViewById(R.id.speakButton);

        store = new TemplateStore(this);
        requestPermissionsIfNeeded();

        findViewById(R.id.debugButton).setOnClickListener(v ->
                startActivity(new Intent(this, DebugActivity.class)));

        // Priority STOP button
        findViewById(R.id.stopButton).setOnClickListener(v -> {
            if (vcm != null) vcm.sendPriorityStop();
        });

        // Directional buttons for manual override
        findViewById(R.id.btnForward).setOnClickListener(v -> sendManual("forward"));
        findViewById(R.id.btnBack).setOnClickListener(v -> sendManual("back"));
        findViewById(R.id.btnLeft).setOnClickListener(v -> sendManual("left"));
        findViewById(R.id.btnRight).setOnClickListener(v -> sendManual("right"));

        speakButton.setOnTouchListener((v, event) -> {
            if (vcm == null || gatt == null) {
                statusText.setText("Not connected to ESP32");
                return false;
            }
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                speakButton.setText("Listening…");
                speakButton.setBackgroundTintList(ColorStateList.valueOf(0xFFE74C3C));
                statusText.setText("Speak now");
                vcm.startListening();
            } else if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                speakButton.setText("Hold to Speak");
                speakButton.setBackgroundTintList(ColorStateList.valueOf(0xFF2980B9));
            }
            return false;
        });
    }

    private void sendManual(String cmd) {
        if (vcm != null) vcm.manualControl(cmd);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        reconnectHandler.removeCallbacksAndMessages(null);
        if (gatt != null) gatt.close();
    }

    private void connectBle() {
        if (isConnecting) return;
        isConnecting = true;

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            statusText.setText("Bluetooth is off");
            isConnecting = false;
            scheduleReconnect();
            return;
        }

        BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(ESP32_MAC);
        } catch (IllegalArgumentException e) {
            statusText.setText("Invalid MAC address");
            isConnecting = false;
            return;
        }

        runOnUiThread(() -> statusText.setText("Connecting to ESP32…"));

        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            isConnecting = false;
            return;
        }

        gatt = device.connectGatt(this, false, new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    runOnUiThread(() -> {
                        statusText.setText("Discovering services…");
                        statusText.setTextColor(0xFF2ECC71);
                    });
                    g.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    isConnecting = false;
                    gatt = null;
                    runOnUiThread(() -> {
                        statusText.setText("Disconnected. Reconnecting…");
                        statusText.setTextColor(0xFFE74C3C);
                        speakButton.setEnabled(false);
                    });
                    scheduleReconnect();
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt g, int status) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    isConnecting = false;
                    vcm = new VoiceCommandManager(MainActivity.this, store, g,
                            new VoiceCommandManager.CommandListener() {
                                @Override
                                public void onResult(DTWMatcher.MatchResult result) {
                                    if (result.matched) {
                                        statusText.setText("Sent: " + result.command.toUpperCase());
                                    } else {
                                        statusText.setText("No Match");
                                    }
                                }
                                @Override public void onError(String msg) { statusText.setText("Error: " + msg); }
                            });
                    runOnUiThread(() -> {
                        statusText.setText("Connected to ESP32");
                        speakButton.setEnabled(true);
                    });
                }
            }
        });
    }

    private void scheduleReconnect() {
        reconnectHandler.removeCallbacksAndMessages(null);
        reconnectHandler.postDelayed(this::connectBle, 5000);
    }

    private void requestPermissionsIfNeeded() {
        String[] perms;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms = new String[]{
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            };
        } else {
            perms = new String[]{
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        }
        ActivityCompat.requestPermissions(this, perms, PERM_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERM_CODE) {
            boolean allGranted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) { allGranted = false; break; }
            }
            if (allGranted) {
                connectBle();
            } else {
                Toast.makeText(this, "Permissions required", Toast.LENGTH_LONG).show();
            }
        }
    }
}
