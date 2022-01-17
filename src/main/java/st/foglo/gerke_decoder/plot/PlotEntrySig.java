package st.foglo.gerke_decoder.plot;

/**
 * Multiple value for plotting the signal.
 */
public class PlotEntrySig extends PlotEntryBase {

    public final double sig;
    public final double threshold;
    public final double ceiling;
    public final double floor;

    public PlotEntrySig(
    		double sig,
    		double threshold,
    		double ceiling,
    		double floor) {
        this.sig = sig;
        this.threshold = threshold;
        this.ceiling = ceiling;
        this.floor = floor;
    }
}

