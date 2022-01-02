package st.foglo.gerke_decoder.decoder.sliding_line;

public final class WeightDot extends WeightBase {
    public WeightDot(int jMax) {
        super(jMax);
    }

    public double w(int j) {
        return 1.0;
    }
}
