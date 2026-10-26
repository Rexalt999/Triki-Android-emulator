package pl.trikiemu.app;

/**
 * Zrodlo ruchu. {@link #next(float[])} jest wolane co 10 ms (100 Hz) przez silnik
 * strumienia, po jednej probce na ramke.
 */
interface MotionSource {
    /** Wypelnia out[0..5] = gx, gy, gz [deg/s], ax, ay, az [g]. */
    void next(float[] out);
}
