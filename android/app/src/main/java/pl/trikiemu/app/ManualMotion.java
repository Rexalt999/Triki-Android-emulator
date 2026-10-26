package pl.trikiemu.app;

/**
 * Sterowanie reczne przyciskami na ekranie - port petli z tools/pc_keyboard.py
 * (tryb gier: obrot/przechyl + FLAP; tryb rzutu: podrzut z obrotami).
 * Kazde wywolanie next() = jeden krok DT = 10 ms.
 */
final class ManualMotion implements MotionSource {
    private static final double DT = Protocol.FRAME_PERIOD_MS / 1000.0;
    private static final double DECAY = Math.pow(0.85, 50.0 / 100.0);
    private static final double MAXANG = 80.0;
    static final double STEP_MIN = 1.0, STEP_MAX = 120.0;
    static final double FLAP_PEAK_MIN = 0.5, FLAP_PEAK_MAX = 30.0;
    private static final double FLAP_CYCLES = 1.5;
    static final int MAX_THROW = 20;

    private static final double THROW_CYCLE_MS = 350.0;
    private static final double THROW_COUNT_A = 3.29, THROW_COUNT_B = -0.2;
    private static final double THROW_GYRO = -300.0;
    private static final int THROW_SPIN_AXIS = 0;
    private static final double AY_MID = 1.5, AY_AMP = 0.8;
    private static final double AZ_MID = -0.9, AZ_AMP = 0.15;
    private static final double WINDUP_MS = 150.0, WINDUP_PEAK = 5.0;
    private static final double LAND_MS = 80.0, LAND_PEAK = 10.0;

    private static final int PH_NONE = 0, PH_WINDUP = 1, PH_SPIN = 2, PH_LAND = 3;

    private double step = 15.0;
    private double flapPeak = 10.0;
    private double flapMs = 120.0;

    private double wx, wy, wz;
    private double roll, pitch;
    private double flapT = -1.0;          // < 0 = brak flapa
    private int throwN = 0;
    private int phase = PH_NONE;
    private double pt = 0.0;

    // ---- komendy (z watku UI) ----

    synchronized void setStep(double v) {
        step = Math.max(STEP_MIN, Math.min(STEP_MAX, v));
    }

    synchronized void setFlapPeak(double v) {
        flapPeak = Math.max(FLAP_PEAK_MIN, Math.min(FLAP_PEAK_MAX, v));
    }

    synchronized void turnLeft()  { if (phase == PH_NONE) wz += step; }   // q
    synchronized void turnRight() { if (phase == PH_NONE) wz -= step; }   // e
    synchronized void xMinus()    { if (phase == PH_NONE) wx -= step; }   // a
    synchronized void xPlus()     { if (phase == PH_NONE) wx += step; }   // d
    synchronized void yPlus()     { if (phase == PH_NONE) wy += step; }   // w
    synchronized void yMinus()    { if (phase == PH_NONE) wy -= step; }   // s
    synchronized void flap()      { if (phase == PH_NONE) flapT = 0.0; }  // f

    synchronized void zero() {
        if (phase != PH_NONE) return;
        wx = wy = wz = 0.0;
        roll = pitch = 0.0;
    }

    /** Rzut z N obrotami (1..20), jak wpisanie dwoch cyfr w trybie rzutu. */
    synchronized void throwIt(int n) {
        if (phase != PH_NONE) return;
        throwN = Math.max(1, Math.min(MAX_THROW, n));
        phase = PH_WINDUP;
        pt = 0.0;
    }

    synchronized boolean isThrowing() {
        return phase != PH_NONE;
    }

    synchronized double[] gyroSnapshot() {
        return new double[] {wx, wy, wz};
    }

    // ---- krok symulacji ----

    @Override
    public synchronized void next(float[] out) {
        final double d2r = Math.PI / 180.0;
        roll = Math.max(-MAXANG, Math.min(MAXANG, roll + wx * DT));
        pitch = Math.max(-MAXANG, Math.min(MAXANG, pitch + wy * DT));
        double ax = Math.sin(pitch * d2r);
        double ay = Math.sin(roll * d2r);
        double az = Math.sqrt(Math.max(0.0, 1.0 - ax * ax - ay * ay));
        double gx = wx, gy = wy, gz = wz;

        boolean hold = false;
        if (phase != PH_NONE) {
            hold = true;
            pt += DT;
            if (phase == PH_WINDUP) {
                double fr = pt / (WINDUP_MS / 1000.0);
                ax = 0.0; ay = 0.0;
                az = 1.0 + (WINDUP_PEAK - 1.0) * Math.min(1.0, fr);
                if (pt >= WINDUP_MS / 1000.0) { phase = PH_SPIN; pt = 0.0; }
            } else if (phase == PH_SPIN) {
                double cycles = Math.max(0.34, (throwN - THROW_COUNT_B) / THROW_COUNT_A);
                double T = cycles * THROW_CYCLE_MS / 1000.0;
                double ang = 2 * Math.PI * cycles * (pt / T);
                double[] v = {0.0, 0.0, 0.0};
                v[THROW_SPIN_AXIS] = THROW_GYRO;
                gx = v[0]; gy = v[1]; gz = v[2];
                ax = 0.0;
                ay = AY_MID + AY_AMP * Math.cos(ang);
                az = AZ_MID + AZ_AMP * Math.sin(ang);
                if (pt >= T) { phase = PH_LAND; pt = 0.0; }
            } else if (phase == PH_LAND) {
                ax = 0.0; ay = 0.0; az = LAND_PEAK;
                if (pt >= LAND_MS / 1000.0) phase = PH_NONE;
            }
        }

        if (flapT >= 0.0 && phase == PH_NONE) {
            double frac = flapT / (flapMs / 1000.0);
            if (frac >= 1.0) {
                flapT = -1.0;
            } else {
                az += flapPeak * Math.sin(Math.PI * frac) * Math.sin(2 * Math.PI * FLAP_CYCLES * frac);
                flapT += DT;
            }
        } else if (flapT >= 0.0) {
            flapT = -1.0;
        }

        out[0] = (float) gx; out[1] = (float) gy; out[2] = (float) gz;
        out[3] = (float) ax; out[4] = (float) ay; out[5] = (float) az;

        if (!hold) {
            wx *= DECAY; wy *= DECAY; wz *= DECAY;
        }
    }
}
