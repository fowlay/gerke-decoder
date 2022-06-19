package st.foglo.gerke_decoder.decoder.dips_find;


public final class DipByTime extends Dip {

    public DipByTime(int q, double strength) {
        super(q, strength);
    }

    @Override
    public int compareTo(Dip o) {
        // chronological order
        return this.q < o.q ? -1 : this.q == o.q ? 0 : 1;
    }
}
