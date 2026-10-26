package pl.trikiemu.app;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelUuid;
import android.os.SystemClock;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.UUID;

/**
 * Emulator kapsla Triki jako BLE peripheral na Androidzie (odpowiednik firmware/src/main.cpp).
 *
 * Reklama: ADV = flags + manufacturer data (0xFF00: a0 0a) + nazwa; SCAN_RSP = 16-bit UUID 0x0001.
 * GATT: Nordic UART (RX/TX/Vendor) + Battery + Device Information (fw "3.2.1-A"). Link otwarty (bez parowania).
 * Po komendzie START (prefiks 20 10) wysyla "ready" 21 00 00 00 00, potem ramki IMU 14 B co 10 ms
 * pocieta na notyfikacje 20/20/2 B (3 ramki = 42 B).
 *
 * Caly stan zmieniany jest wylacznie w watku "triki-ble" (Handler h); callbacki GATT (watki binder)
 * tylko odpowiadaja na zadania ATT i przekazuja zdarzenia do tego watku.
 *
 * UWAGA (ograniczenie platformy): Android nie pozwala aplikacji ustawic adresu BLE, z ktorego sie
 * reklamuje (system uzywa wlasnego adresu losowego), wiec nie da sie odtworzyc MAC prawdziwego kapsla.
 */
@SuppressLint("MissingPermission")
final class TrikiPeripheral {

    interface Listener {
        /** Wolane z watku "triki-ble". */
        void onLog(String msg);
        /** Zmienil sie stan (polaczenie/strumien/reklama). Wolane z watku "triki-ble". */
        void onChanged();
    }

    private static final String PREFS = "trikiemu";
    private static final String KEY_ORIG_NAME = "orig_bt_name";
    /** Maks. liczba kawalkow w kolejce TX = 2 cykle (3 ramki): ogranicza opoznienie do ~60 ms. */
    private static final int MAX_QUEUE_CHUNKS = 2 * Protocol.CYCLE_FRAMES;
    private static final long BUSY_TIMEOUT_MS = 500;

    private static final Object TICK_TOKEN = new Object();

    private final Context ctx;
    private final Listener listener;
    private final SharedPreferences prefs;
    private final HandlerThread thread;
    private final Handler h;
    private final BluetoothManager mgr;
    private final BluetoothAdapter adapter;

    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattServer gatt;
    private BluetoothGattCharacteristic txChar;
    private BluetoothDevice central;

    private volatile MotionSource source = new RestMotion();
    private final float[] motion = new float[6];
    private final float[] lastMotion = new float[6];

    // ---- stan (zapisywany z watku h, czytany tez z UI) ----
    private volatile boolean wantAdvertising = false;
    private volatile boolean advertising = false;
    private volatile boolean connected = false;
    private volatile boolean streaming = false;
    private volatile boolean txSubscribed = false;
    private volatile boolean batSubscribed = false;
    private volatile int dropped = 0;
    private volatile long frames = 0;
    private volatile int mtu = 23;
    private volatile String centralAddr = "";
    private volatile String advName = "";
    private volatile byte vendorVal = 0;

    // ---- strumien (tylko watek h) ----
    private final byte[] acc = new byte[Protocol.CYCLE_LEN];
    private int accLen = 0;
    private final ArrayDeque<byte[]> txQueue = new ArrayDeque<>();
    private boolean notifyBusy = false;
    private long busySince = 0;
    private long frameBase = 0;
    private long frameN = 0;
    private long lastNotifyErrLog = 0;
    private final HashSet<Integer> seenOpcodes = new HashSet<>();
    private final ArrayDeque<BluetoothGattService> pendingServices = new ArrayDeque<>();

    TrikiPeripheral(Context c, Listener l) {
        ctx = c.getApplicationContext();
        listener = l;
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        thread = new HandlerThread("triki-ble");
        thread.start();
        h = new Handler(thread.getLooper());
        mgr = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = mgr == null ? null : mgr.getAdapter();
    }

    // =====================================================================
    // API publiczne (dowolny watek)
    // =====================================================================

    void setSource(MotionSource s) { source = s; }

    boolean hasBluetooth() { return adapter != null; }

    boolean isBluetoothEnabled() {
        try { return adapter != null && adapter.isEnabled(); } catch (SecurityException e) { return false; }
    }

    boolean isWantAdvertising() { return wantAdvertising; }
    boolean isAdvertising()     { return advertising; }
    boolean isConnected()       { return connected; }
    boolean isStreaming()       { return streaming; }
    boolean isTxSubscribed()    { return txSubscribed; }
    int getDropped()            { return dropped; }
    long getFrames()            { return frames; }
    int getMtu()                { return mtu; }
    String getCentralAddr()     { return centralAddr; }
    String getAdvName()         { return advName; }
    byte getVendorValue()       { return vendorVal; }

    float[] getLastMotion() {
        synchronized (lastMotion) { return lastMotion.clone(); }
    }

    /** Wlacza reklame (parowanie). Nazwa BT telefonu jest na czas dzialania zmieniona na {@code name}. */
    void start(final String name) {
        h.post(new Runnable() { @Override public void run() { doStart(name); } });
    }

    /** Wylacza reklame, rozlacza centrala i przywraca oryginalna nazwe Bluetooth telefonu. */
    void stop() {
        h.post(new Runnable() { @Override public void run() { doStop(); } });
    }

    /** Przywraca nazwe BT zapamietana, gdy aplikacja zostala zabita w trakcie dzialania. */
    void restoreSavedName() {
        h.post(new Runnable() { @Override public void run() { if (!wantAdvertising) restoreName(); } });
    }

    void destroy() {
        h.post(new Runnable() {
            @Override public void run() {
                doStop();
                thread.quitSafely();
            }
        });
    }

    // =====================================================================
    // Start / stop
    // =====================================================================

    private void log(String s) { listener.onLog(s); }

    private void changed() { listener.onChanged(); }

    private void doStart(String name) {
        if (wantAdvertising) return;
        try {
            if (adapter == null || !adapter.isEnabled()) {
                log("Bluetooth jest wyłączony - włącz go i spróbuj ponownie.");
                return;
            }
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) {
                log("To urządzenie nie obsługuje reklamowania BLE (tryb peripheral).");
                return;
            }
            wantAdvertising = true;
            advName = name;

            String cur = adapter.getName();
            if (!prefs.contains(KEY_ORIG_NAME) && cur != null) {
                prefs.edit().putString(KEY_ORIG_NAME, cur).apply();
            }
            if (!name.equals(cur)) {
                boolean ok = adapter.setName(name);
                log("Zmiana nazwy Bluetooth na \"" + name + "\": " + (ok ? "OK" : "odmowa systemu"));
            }
            changed();
            awaitName(name, 0);
        } catch (SecurityException e) {
            wantAdvertising = false;
            log("Brak uprawnień Bluetooth: " + e.getMessage());
            changed();
        }
    }

    /** Nazwa w stosie zmienia sie asynchronicznie - czekamy (max 3 s), zanim zbudujemy reklame. */
    private void awaitName(final String name, final int attempt) {
        if (!wantAdvertising) return;
        String cur = null;
        try { cur = adapter.getName(); } catch (SecurityException ignored) { }
        if (name.equals(cur)) {
            openServer();
            return;
        }
        if (attempt >= 30) {
            log("UWAGA: system nie zmienił nazwy BT (jest \"" + cur + "\") - reklama użyje bieżącej nazwy.");
            advName = cur == null ? "" : cur;
            openServer();
            return;
        }
        h.postDelayed(new Runnable() {
            @Override public void run() { awaitName(name, attempt + 1); }
        }, 100);
    }

    private void openServer() {
        if (!wantAdvertising) return;
        try {
            gatt = mgr.openGattServer(ctx, gattCallback);
        } catch (SecurityException e) {
            gatt = null;
            log("Brak uprawnień Bluetooth: " + e.getMessage());
        }
        if (gatt == null) {
            log("Nie udało się otworzyć serwera GATT.");
            wantAdvertising = false;
            restoreName();
            changed();
            return;
        }
        buildServices();
        addNextService();
    }

    private void doStop() {
        boolean was = wantAdvertising || advertising || gatt != null;
        wantAdvertising = false;
        h.removeCallbacksAndMessages(null);
        stopStreaming(false);
        try {
            if (advertiser != null) advertiser.stopAdvertising(advCallback);
        } catch (RuntimeException ignored) { }
        advertising = false;
        if (gatt != null) {
            try {
                if (central != null) gatt.cancelConnection(central);
            } catch (RuntimeException ignored) { }
            try {
                gatt.close();
            } catch (RuntimeException ignored) { }
            gatt = null;
        }
        pendingServices.clear();
        central = null;
        connected = false;
        txSubscribed = false;
        batSubscribed = false;
        centralAddr = "";
        mtu = 23;
        txChar = null;
        restoreName();
        if (was) log("BLE wyłączone.");
        changed();
    }

    private void restoreName() {
        String orig = prefs.getString(KEY_ORIG_NAME, null);
        if (orig == null || adapter == null) return;
        try {
            if (!adapter.isEnabled()) return;           // zostaje zapamietana - przywrocimy przy nastepnym starcie
            String cur = adapter.getName();
            boolean ok = orig.equals(cur) || adapter.setName(orig);
            if (ok) {
                prefs.edit().remove(KEY_ORIG_NAME).apply();
                if (!orig.equals(cur)) log("Przywrócono nazwę Bluetooth: \"" + orig + "\"");
            }
        } catch (SecurityException ignored) { }
    }

    // =====================================================================
    // Profil GATT
    // =====================================================================

    private void buildServices() {
        pendingServices.clear();

        // --- Nordic UART Service ---
        BluetoothGattService nus = new BluetoothGattService(Protocol.NUS_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic rx = new BluetoothGattCharacteristic(
                Protocol.NUS_RX,
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        BluetoothGattCharacteristic tx = new BluetoothGattCharacteristic(
                Protocol.NUS_TX,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                0);
        tx.addDescriptor(new BluetoothGattDescriptor(Protocol.CCCD,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
        BluetoothGattCharacteristic vendor = new BluetoothGattCharacteristic(
                Protocol.NUS_VENDOR,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE);
        nus.addCharacteristic(rx);
        nus.addCharacteristic(tx);
        nus.addCharacteristic(vendor);
        txChar = tx;
        pendingServices.add(nus);

        // --- Battery Service ---
        BluetoothGattService bat = new BluetoothGattService(Protocol.BATTERY_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic lvl = new BluetoothGattCharacteristic(
                Protocol.BATTERY_LEVEL,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);
        lvl.addDescriptor(new BluetoothGattDescriptor(Protocol.CCCD,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
        bat.addCharacteristic(lvl);
        pendingServices.add(bat);

        // --- Device Information: tylko Firmware Revision ---
        BluetoothGattService dis = new BluetoothGattService(Protocol.DIS_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic fw = new BluetoothGattCharacteristic(
                Protocol.DIS_FIRMWARE,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        dis.addCharacteristic(fw);
        pendingServices.add(dis);
    }

    /** Android wymaga dodawania uslug pojedynczo - kolejna po onServiceAdded. */
    private void addNextService() {
        if (!wantAdvertising || gatt == null) return;
        BluetoothGattService s = pendingServices.poll();
        if (s == null) {
            startAdv();
            return;
        }
        boolean ok;
        try {
            ok = gatt.addService(s);
        } catch (RuntimeException e) {
            ok = false;
        }
        if (!ok) {
            log("Nie udało się dodać usługi GATT " + s.getUuid());
            doStop();
        }
    }

    private byte[] readValue(UUID u) {
        if (Protocol.NUS_VENDOR.equals(u)) return new byte[] {vendorVal};
        if (Protocol.BATTERY_LEVEL.equals(u)) return new byte[] {100};
        if (Protocol.DIS_FIRMWARE.equals(u)) return Protocol.FIRMWARE_REV.getBytes(StandardCharsets.US_ASCII);
        return null;
    }

    private final BluetoothGattServerCallback gattCallback = new BluetoothGattServerCallback() {

        @Override
        public void onConnectionStateChange(final BluetoothDevice device, final int status, final int newState) {
            h.post(new Runnable() {
                @Override public void run() { handleConnectionState(device, status, newState); }
            });
        }

        @Override
        public void onServiceAdded(final int status, final BluetoothGattService service) {
            h.post(new Runnable() {
                @Override public void run() {
                    if (!wantAdvertising) return;
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        log("Błąd dodawania usługi " + service.getUuid() + " (status " + status + ")");
                        doStop();
                        return;
                    }
                    addNextService();
                }
            });
        }

        @Override
        public void onMtuChanged(BluetoothDevice device, final int newMtu) {
            mtu = newMtu;
            h.post(new Runnable() {
                @Override public void run() { log("MTU = " + newMtu); changed(); }
            });
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                BluetoothGattCharacteristic characteristic) {
            BluetoothGattServer g = gatt;
            if (g == null) return;
            byte[] v = readValue(characteristic.getUuid());
            try {
                if (v == null) {
                    g.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null);
                } else if (offset > v.length) {
                    g.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
                } else {
                    g.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                            Arrays.copyOfRange(v, offset, v.length));
                }
            } catch (RuntimeException ignored) { }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite, boolean responseNeeded,
                                                 int offset, final byte[] value) {
            BluetoothGattServer g = gatt;
            int status = BluetoothGatt.GATT_SUCCESS;
            UUID u = characteristic.getUuid();
            if (Protocol.NUS_RX.equals(u)) {
                h.post(new Runnable() {
                    @Override public void run() { handleRx(value); }
                });
            } else if (Protocol.NUS_VENDOR.equals(u)) {
                if (value != null && value.length > 0) vendorVal = (byte) (value[0] & 0x01);   // maskowane do 1 bitu
            } else {
                status = BluetoothGatt.GATT_WRITE_NOT_PERMITTED;
            }
            if (responseNeeded && g != null) {
                try {
                    g.sendResponse(device, requestId, status, offset, value);
                } catch (RuntimeException ignored) { }
            }
        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset,
                                            BluetoothGattDescriptor descriptor) {
            BluetoothGattServer g = gatt;
            if (g == null) return;
            try {
                if (Protocol.CCCD.equals(descriptor.getUuid())) {
                    UUID cu = descriptor.getCharacteristic().getUuid();
                    boolean on = Protocol.NUS_TX.equals(cu) ? txSubscribed : batSubscribed;
                    byte[] v = on ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                  : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
                    if (offset > v.length) {
                        g.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
                    } else {
                        g.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                                Arrays.copyOfRange(v, offset, v.length));
                    }
                } else {
                    g.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null);
                }
            } catch (RuntimeException ignored) { }
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattDescriptor descriptor,
                                             boolean preparedWrite, boolean responseNeeded,
                                             int offset, byte[] value) {
            BluetoothGattServer g = gatt;
            int status = BluetoothGatt.GATT_SUCCESS;
            if (Protocol.CCCD.equals(descriptor.getUuid())) {
                final boolean on = value != null && value.length >= 1 && (value[0] & 0x01) != 0;
                final boolean isTx = Protocol.NUS_TX.equals(descriptor.getCharacteristic().getUuid());
                h.post(new Runnable() {
                    @Override public void run() {
                        if (isTx) {
                            txSubscribed = on;
                            log("Notyfikacje TX: " + (on ? "włączone" : "wyłączone"));
                        } else {
                            batSubscribed = on;
                        }
                        changed();
                    }
                });
            } else {
                status = BluetoothGatt.GATT_WRITE_NOT_PERMITTED;
            }
            if (responseNeeded && g != null) {
                try {
                    g.sendResponse(device, requestId, status, offset, value);
                } catch (RuntimeException ignored) { }
            }
        }

        @Override
        public void onExecuteWrite(BluetoothDevice device, int requestId, boolean execute) {
            BluetoothGattServer g = gatt;
            if (g == null) return;
            try {
                g.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
            } catch (RuntimeException ignored) { }
        }

        @Override
        public void onNotificationSent(BluetoothDevice device, final int status) {
            h.post(new Runnable() {
                @Override public void run() {
                    notifyBusy = false;
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        long now = SystemClock.uptimeMillis();
                        if (now - lastNotifyErrLog > 1000) {      // nie zalewaj dziennika (100 notyfikacji/s)
                            lastNotifyErrLog = now;
                            log("Błąd wysyłki notyfikacji (status " + status + ")");
                        }
                    }
                    pump();
                }
            });
        }
    };

    private void handleConnectionState(BluetoothDevice device, int status, int newState) {
        if (gatt == null) return;
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            if (central != null && !central.equals(device)) {
                // emulujemy jeden kapsel = jeden central
                try { gatt.cancelConnection(device); } catch (RuntimeException ignored) { }
                log("Odrzucono drugiego centrala " + device.getAddress());
                return;
            }
            central = device;
            connected = true;
            seenOpcodes.clear();
            centralAddr = device.getAddress();
            log("Central POŁĄCZONY: " + centralAddr);
            changed();
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            if (central != null && !central.equals(device)) return;
            log("Central ROZŁĄCZONY (status " + status + ")");
            central = null;
            connected = false;
            txSubscribed = false;
            batSubscribed = false;
            centralAddr = "";
            mtu = 23;
            stopStreaming(true);
            if (wantAdvertising) {
                // Android zwykle przerywa reklame po polaczeniu - wznawiamy (jak firmware)
                restartAdv();
            }
            changed();
        }
    }

    // =====================================================================
    // Reklama
    // =====================================================================

    private final AdvertiseCallback advCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            h.post(new Runnable() {
                @Override public void run() {
                    advertising = true;
                    log("Reklama ON jako \"" + advName + "\"");
                    changed();
                }
            });
        }

        @Override
        public void onStartFailure(final int errorCode) {
            h.post(new Runnable() {
                @Override public void run() {
                    if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) {
                        advertising = true;
                        changed();
                        return;
                    }
                    advertising = false;
                    log("Reklama NIEUDANA: " + advErrorName(errorCode));
                    changed();
                }
            });
        }
    };

    private static String advErrorName(int code) {
        switch (code) {
            case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE:       return "dane reklamy za duże (skróć nazwę)";
            case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS: return "zbyt wiele aktywnych reklam";
            case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED:      return "już uruchomiona";
            case AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR:       return "błąd wewnętrzny stosu BT";
            case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED:  return "funkcja nieobsługiwana przez urządzenie";
            default:                                                      return "kod " + code;
        }
    }

    private void startAdv() {
        if (!wantAdvertising || advertiser == null) return;
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build();
        // ADV: flags (dodaje system dla polaczalnej reklamy) + manufacturer data + nazwa (nazwa adaptera BT)
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .setIncludeTxPowerLevel(false)
                .addManufacturerData(Protocol.MFR_COMPANY_ID, Protocol.MFR_PAYLOAD)
                .build();
        // SCAN_RSP: 16-bit Service UUID 0x0001 (bez 128-bitowego UUID NUS - kapsel go nie reklamuje)
        AdvertiseData scanRsp = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(new ParcelUuid(Protocol.SCAN_RSP_SERVICE_UUID))
                .build();
        try {
            advertiser.startAdvertising(settings, data, scanRsp, advCallback);
        } catch (RuntimeException e) {
            log("Reklama NIEUDANA: " + e.getMessage());
        }
    }

    private void restartAdv() {
        try {
            if (advertiser != null) advertiser.stopAdvertising(advCallback);
        } catch (RuntimeException ignored) { }
        advertising = false;
        h.postDelayed(new Runnable() {
            @Override public void run() { startAdv(); }
        }, 300);
    }

    // =====================================================================
    // Komendy RX i strumien
    // =====================================================================

    private void handleRx(byte[] v) {
        if (Protocol.isStart(v)) {
            log("RX START: " + Protocol.hex(v));
            if (!streaming && connected) startStreaming();
        } else if (Protocol.isStop(v)) {
            log("RX STOP: " + Protocol.hex(v));
            if (streaming) stopStreaming(true);
        } else if (v != null && v.length > 0) {
            // Prawdziwa apka co ok. 1 s wysyla uwierzytelnienie (09/0a ...) - emulator go nie obsluguje
            // (zob. README: zapis wyniku wymaga prawdziwego kapsla). Logujemy tylko pierwsze wystapienie opcode'u.
            int op = v[0] & 0xFF;
            if (seenOpcodes.add(op)) {
                log("RX (nieobsługiwana komenda, kolejne ukryte): " + Protocol.hex(Arrays.copyOf(v, Math.min(v.length, 12))));
            }
        }
    }

    private void startStreaming() {
        accLen = 0;
        txQueue.clear();
        notifyBusy = false;
        dropped = 0;
        frames = 0;
        txQueue.add(Protocol.READY.clone());           // gotowosc 21 - jako pierwsza, osobna wiadomosc
        streaming = true;
        frameBase = SystemClock.uptimeMillis();
        frameN = 0;
        h.removeCallbacksAndMessages(TICK_TOKEN);
        scheduleTick();
        log("START -> strumień ON");
        changed();
        pump();
    }

    private void stopStreaming(boolean announce) {
        boolean was = streaming;
        streaming = false;
        accLen = 0;
        txQueue.clear();
        notifyBusy = false;
        h.removeCallbacksAndMessages(TICK_TOKEN);
        if (was && announce) {
            log("STOP -> strumień OFF (pominięte cykle: " + dropped + ")");
            changed();
        }
    }

    private final Runnable tickRunnable = new Runnable() {
        @Override public void run() { tick(); }
    };

    /** Ramka k (k = 1, 2, ...) jest "na czas" o frameBase + k * 10 ms. */
    private void scheduleTick() {
        long due = frameBase + (frameN + 1) * Protocol.FRAME_PERIOD_MS;
        long now = SystemClock.uptimeMillis();
        if (now - due > 100) {                      // zbyt duze opoznienie: nie nadrabiamy lawina ramek
            frameBase = now;
            frameN = 0;
            due = now + Protocol.FRAME_PERIOD_MS;
        }
        h.postAtTime(tickRunnable, TICK_TOKEN, due);
    }

    private void tick() {
        if (!streaming) return;
        produceFrame();
        scheduleTick();
        pump();
    }

    private void produceFrame() {
        source.next(motion);
        synchronized (lastMotion) {
            System.arraycopy(motion, 0, lastMotion, 0, 6);
        }
        // status: bit0 = przycisk; w grach nieuzywany -> 0x00
        Protocol.buildFrame(acc, accLen, 0x00, motion);
        accLen += Protocol.FRAME_LEN;
        frameN++;
        frames++;
        if (accLen >= Protocol.CYCLE_LEN) {
            enqueueCycle();
            accLen = 0;
        }
    }

    /**
     * Brak licznika/CRC: zgubiony kawalek rozrywa ramke. Dlatego cykl 3 ramek (42 B) wchodzi do
     * kolejki w calosci albo wcale (pominiecie = licznik "dropped"), a kawalki ida w kolejnosci.
     */
    private void enqueueCycle() {
        if (txQueue.size() + Protocol.CYCLE_FRAMES > MAX_QUEUE_CHUNKS) {
            dropped++;
            return;
        }
        txQueue.addAll(Protocol.split(acc));
    }

    /** Wysyla kolejny kawalek, gdy poprzednia notyfikacja zostala potwierdzona (onNotificationSent). */
    private void pump() {
        if (!connected || central == null || gatt == null || txChar == null) return;
        long now = SystemClock.uptimeMillis();
        if (notifyBusy) {
            if (now - busySince < BUSY_TIMEOUT_MS) return;
            notifyBusy = false;                      // zgubione potwierdzenie - nie blokujemy strumienia
        }
        byte[] chunk = txQueue.peek();
        if (chunk == null) return;
        if (sendNotify(chunk)) {
            txQueue.poll();
            notifyBusy = true;
            busySince = now;
        }
        // przy niepowodzeniu kawalek zostaje na czele kolejki i idzie ponownie przy nastepnym ticku
    }

    private boolean sendNotify(byte[] chunk) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                return gatt.notifyCharacteristicChanged(central, txChar, false, chunk)
                        == BluetoothStatusCodes.SUCCESS;
            } else {
                txChar.setValue(chunk);
                return gatt.notifyCharacteristicChanged(central, txChar, false);
            }
        } catch (RuntimeException e) {
            return false;
        }
    }
}
