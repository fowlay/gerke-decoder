package st.foglo.gerke_decoder.detector;

public final class Signal {

    // TODO, consider fBest as double

    public final double[] sig;
    public final int fBest;
    public final int clipLevel;


    public Signal(double[] sig, int fBest, int clipLevel) {
        this.sig = sig;
        this.fBest = fBest;
        this.clipLevel = clipLevel;

    }
}
