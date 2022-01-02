package st.foglo.gerke_decoder.decoder.sliding_line;

public final class WeightTwoDots extends WeightBase {
    public WeightTwoDots(int jMax) {
        super(jMax);
    }

    public double w(int j) {
        final int j3 = jMax/3;
        return j > j3 || j < -j3 ? 1.0 : 0.0;
    }
}
