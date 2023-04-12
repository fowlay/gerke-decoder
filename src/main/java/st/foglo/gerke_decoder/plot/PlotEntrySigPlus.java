package st.foglo.gerke_decoder.plot;

public final class PlotEntrySigPlus extends PlotEntrySig {

    public final double sigAvg;

    public PlotEntrySigPlus(
            double sig,
            double sigAvg,
            double threshold,
            double ceiling,
            double floor) {
        super(sig, threshold, ceiling, floor);

        this.sigAvg = sigAvg;
    }

}
