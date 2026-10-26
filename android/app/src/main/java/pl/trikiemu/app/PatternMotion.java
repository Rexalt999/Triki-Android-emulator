package pl.trikiemu.app;

/**
 * Wzorce testowe z tools/pc_motion_feed.py ("spin", "tilt"). Czas liczony deterministycznie:
 * kazde wywolanie next() = 10 ms.
 */
final class PatternMotion implements MotionSource {
    static final int SPIN = 0;
    static final int TILT = 1;

    private static final double DT = Protocol.FRAME_PERIOD_MS / 1000.0;
    private final int pattern;
    private double t = 0.0;

    PatternMotion(int pattern) {
        this.pattern = pattern;
    }

    @Override
    public synchronized void next(float[] out) {
        if (pattern == TILT) {
            double roll = Math.sin(2 * Math.PI * 0.25 * t);   // 0.25 Hz
            out[0] = 0f; out[1] = 0f; out[2] = 0f;
            out[3] = (float) Math.sin(roll); out[4] = 0f; out[5] = (float) Math.cos(roll);
        } else {
            double gz = 200.0 * Math.sin(2 * Math.PI * 0.5 * t);
            double gx = 30.0 * Math.sin(2 * Math.PI * 0.5 * t + 1.0);
            out[0] = (float) gx; out[1] = 0f; out[2] = (float) gz;
            out[3] = 0f; out[4] = 0f; out[5] = 1f;
        }
        t += DT;
    }
}
