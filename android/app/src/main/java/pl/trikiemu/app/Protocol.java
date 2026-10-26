package pl.trikiemu.app;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Kontrakt protokolu kapsla Triki (zob. firmware/README.md): UUID-y, komendy RX,
 * format ramki IMU (14 B) i ciecie strumienia na notyfikacje po 20 B.
 * Czysta Java (bez Androida) - mozna testowac na JVM.
 */
final class Protocol {
    private Protocol() {}

    // ---- Nordic UART Service ----
    static final UUID NUS_SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID NUS_RX      = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"); // write (telefon->urzadzenie)
    static final UUID NUS_TX      = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"); // notify (urzadzenie->telefon)
    static final UUID NUS_VENDOR  = UUID.fromString("6e400004-b5a3-f393-e0a9-e50e24dcca9e"); // read/write (bit0 = LED)

    // ---- Standardowe uslugi ----
    static final UUID BATTERY_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    static final UUID BATTERY_LEVEL   = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    static final UUID DIS_SERVICE     = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb");
    static final UUID DIS_FIRMWARE    = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb");
    static final UUID CCCD            = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /** 16-bitowy Service UUID 0x0001 w scan response (po nim appka filtruje skan). */
    static final UUID SCAN_RSP_SERVICE_UUID = UUID.fromString("00000001-0000-1000-8000-00805f9b34fb");

    // ---- Tozsamosc ----
    static final String DEFAULT_DEVICE_NAME = "Triki 1234567890";
    static final String FIRMWARE_REV = "3.2.1-A";
    static final int MFR_COMPANY_ID = 0xFF00;
    static final byte[] MFR_PAYLOAD = {(byte) 0xA0, 0x0A};
    /** Max dlugosc nazwy: ADV = flags(3) + mfr(6) + name(2+n) <= 31 B -> n <= 20. */
    static final int MAX_NAME_LEN = 20;

    // ---- Skale sprzetowe oczekiwane przez format ramki (LSM6DSL) ----
    static final float GYRO_SCALE  = 131.0f;   // LSB/(deg/s)
    static final float ACCEL_SCALE = 2048.0f;  // LSB/g

    // ---- Ramki ----
    static final int FRAME_LEN = 14;
    static final int CYCLE_FRAMES = 3;
    static final int CYCLE_LEN = FRAME_LEN * CYCLE_FRAMES; // 42 B
    static final int TX_CHUNK = 20;
    static final int FRAME_PERIOD_MS = 10;                  // 100 Hz

    /** Ramka "ready" wysylana raz po START. */
    static final byte[] READY = {0x21, 0x00, 0x00, 0x00, 0x00};

    static boolean isStart(byte[] v) {
        return v != null && v.length >= 2 && v[0] == 0x20 && v[1] == 0x10;
    }

    static boolean isStop(byte[] v) {
        return v != null && v.length >= 2 && v[0] == 0x20 && v[1] == 0x00;
    }

    /** Jak lroundf() w C: zaokraglenie "pol od zera", przyciete do zakresu int16. */
    static short clampToI16(float v) {
        if (v > 32767.0f) return 32767;
        if (v < -32768.0f) return -32768;
        double d = v;
        long r = (long) (d < 0 ? -Math.floor(-d + 0.5) : Math.floor(d + 0.5));
        return (short) r;
    }

    /**
     * Buduje ramke 14 B w f[off..off+13]. m = {gx,gy,gz [deg/s], ax,ay,az [g]}.
     * Uklad: 0x22, status, 6x int16 little-endian (gyro XYZ, accel XYZ).
     */
    static void buildFrame(byte[] f, int off, int status, float[] m) {
        f[off] = 0x22;
        f[off + 1] = (byte) status;
        for (int i = 0; i < 6; i++) {
            float scale = (i < 3) ? GYRO_SCALE : ACCEL_SCALE;
            short s = clampToI16(m[i] * scale);
            f[off + 2 + 2 * i] = (byte) (s & 0xFF);
            f[off + 3 + 2 * i] = (byte) ((s >> 8) & 0xFF);
        }
    }

    /** Tnie wiadomosc na kawalki po {@link #TX_CHUNK} B (42 B -> 20/20/2). */
    static List<byte[]> split(byte[] msg) {
        List<byte[]> out = new ArrayList<>();
        for (int o = 0; o < msg.length; o += TX_CHUNK) {
            int n = Math.min(TX_CHUNK, msg.length - o);
            byte[] c = new byte[n];
            System.arraycopy(msg, o, c, 0, n);
            out.add(c);
        }
        return out;
    }

    static String hex(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02x", b[i] & 0xFF));
        }
        return sb.toString();
    }
}
