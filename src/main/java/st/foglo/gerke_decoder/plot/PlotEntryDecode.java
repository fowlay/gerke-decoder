package st.foglo.gerke_decoder.plot;

/**
 * Single value for plotting a decoded signal.
 */
public final class PlotEntryDecode extends PlotEntryBase {

    public static final double height = 0.05;

    public final double dec;

    public PlotEntryDecode(double dec) {
        this.dec = dec;
    }
}
