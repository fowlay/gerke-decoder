package st.foglo.gerke_decoder.decoder.sliding_line;

public abstract class WeightBase {
    // returns weight for j in [-jMax, ..., 0, ..., jMax]
    final int jMax;
    public WeightBase(int jMax) {
        this.jMax = jMax;
    }
    public abstract double w(int j);
}
