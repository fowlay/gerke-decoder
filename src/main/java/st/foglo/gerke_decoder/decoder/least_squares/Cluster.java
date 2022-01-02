package st.foglo.gerke_decoder.decoder.least_squares;

import java.util.ArrayList;
import java.util.List;

public final class Cluster {
    public final Integer lowestKey;
    public final List<Integer> members = new ArrayList<Integer>();

    public Cluster(Integer a) {
        lowestKey = a;
        members.add(a);
    }

    public void add(Integer b) {
        members.add(b);
    }
}
