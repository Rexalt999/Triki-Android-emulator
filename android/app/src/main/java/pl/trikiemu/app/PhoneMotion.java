package pl.trikiemu.app;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Ruch z czujnikow telefonu (odpowiednik IMU na M5StickC Plus2): akcelerometr [m/s^2 -> g]
 * i zyroskop [rad/s -> deg/s]. Osie przekazywane bez przemapowania (jak w firmware M5);
 * telefon lezacy ekranem do gory daje az = +1 g.
 */
final class PhoneMotion implements MotionSource, SensorEventListener {
    private static final float G = 9.80665f;
    private static final float RAD2DEG = (float) (180.0 / Math.PI);

    private final SensorManager sm;
    private final Sensor accel;
    private final Sensor gyro;
    private boolean registered = false;

    private volatile float ax = 0f, ay = 0f, az = 1f;
    private volatile float gx = 0f, gy = 0f, gz = 0f;

    PhoneMotion(Context ctx) {
        sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        accel = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyro = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
    }

    boolean hasAccel() { return accel != null; }

    boolean hasGyro() { return gyro != null; }

    /** Probkowanie 100 Hz (10 000 us) - ponizej limitu 200 Hz bez uprawnienia HIGH_SAMPLING_RATE_SENSORS. */
    synchronized void start() {
        if (registered || sm == null) return;
        if (accel != null) sm.registerListener(this, accel, 10000);
        if (gyro != null) sm.registerListener(this, gyro, 10000);
        registered = true;
    }

    synchronized void stop() {
        if (!registered || sm == null) return;
        sm.unregisterListener(this);
        registered = false;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            ax = e.values[0] / G; ay = e.values[1] / G; az = e.values[2] / G;
        } else if (e.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            gx = e.values[0] * RAD2DEG; gy = e.values[1] * RAD2DEG; gz = e.values[2] * RAD2DEG;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void next(float[] out) {
        out[0] = gx; out[1] = gy; out[2] = gz;
        if (accel != null) {
            out[3] = ax; out[4] = ay; out[5] = az;
        } else {
            out[3] = 0f; out[4] = 0f; out[5] = 1f;   // brak akcelerometru: spoczynek
        }
    }
}
