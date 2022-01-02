package st.foglo.gerke_decoder.decoder.dips_find;


public abstract class Dip implements Comparable<Dip> {
	
    public final int q;
    public final double strength;

    public Dip(int q, double strength) {
        this.q = q;
        this.strength = strength;
    }

}
