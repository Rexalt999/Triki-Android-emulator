package pl.trikiemu.app;

/** Spoczynek: gyro 0, accel (0, 0, 1 g) - domyslne zrodlo na golym ESP32. */
final class RestMotion implements MotionSource {
    @Override
    public void next(float[] out) {
        out[0] = 0f; out[1] = 0f; out[2] = 0f;
        out[3] = 0f; out[4] = 0f; out[5] = 1f;
    }
}
