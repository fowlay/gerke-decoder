package st.foglo.gerke_decoder.decoder.dips_find;


public final class DipByStrength extends Dip {

    public DipByStrength(int q, double strength) {
        super(q, strength);
    }

    @Override
    public int compareTo(Dip o) {
        // strongest elements at beginning of set
        return this.strength < o.strength ? 1 : this.strength == o.strength ? 0 : -1;
    }
}
