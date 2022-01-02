package st.foglo.gerke_decoder.decoder;

public final class Trans {

    public final int q;
    public final boolean rise;
    public final double dipAcc;
    public final double spikeAcc;
    public final double ceiling;
    public final double floor;

    public Trans(int q, boolean rise, double ceiling, double floor) {
        this(q, rise, -1.0, ceiling, floor);
    }

    public Trans(int q, boolean rise, double sigAcc, double ceiling, double floor) {
        this.q = q;
        this.rise = rise;
        this.ceiling = ceiling;
        this.floor = floor;
        if (rise) {
            this.dipAcc = sigAcc;
            this.spikeAcc = -1.0;
        }
        else {
            this.spikeAcc = sigAcc;
            this.dipAcc = -1.0;
        }
    }

}
