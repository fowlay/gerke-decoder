package st.foglo.gerke_decoder.decoder.pattern_match;

import java.util.ArrayList;
import java.util.List;

import st.foglo.gerke_decoder.decoder.Trans;

public final class CharData {

    public final List<Trans> transes;
    private Trans lastAdded = null;

    public CharData() {
        this.transes = new ArrayList<Trans>();
    }

    public CharData(Trans trans) {
        this.transes = new ArrayList<Trans>();
        add(trans);
    }

    public void add(Trans trans) {
        transes.add(trans);
        lastAdded = trans;
    }

    public boolean isComplete() {
        return lastAdded.rise == false;
    }

    public boolean isEmpty() {
        return transes.isEmpty();
    }

    public Trans getLastAdded() {
        return lastAdded;
    }

}
