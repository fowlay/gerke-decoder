package st.foglo.gerke_decoder.plot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import st.foglo.gerke_decoder.lib.Compute;

public final class HistEntries {

    public final int binWidth;   // ms
    final Map<Integer, Integer> binsTones = new HashMap<Integer, Integer>();
    final Map<Integer, Integer> binsSpaces = new HashMap<Integer, Integer>();

    final double tuMillis;
    final double tsLength;

    public final List<Double> widthsOfTones = new ArrayList<Double>();
    public final List<Double> widthsOfSpaces = new ArrayList<Double>();

    public HistEntries(double tuMillis, double tsLength) {
        this.tuMillis = tuMillis;
        this.tsLength = tsLength;
        this.binWidth = Compute.ensureEven((int)Math.round(tsLength*tuMillis));
    }

    public void addEntry(int mode, int slices) {
        double width = slices*tsLength*tuMillis;
        if (mode == 1) {
            widthsOfTones.add(Double.valueOf(width));

            final Integer index = Integer.valueOf(((int)Math.round(width))/binWidth);
            Integer current = binsTones.get(index);
            if (current == null) {
                binsTones.put(index, Integer.valueOf(1));
            }
            else {
                binsTones.put(index, Integer.valueOf(current.intValue()+1));
            }
        }
        else if (mode == 0) {
            widthsOfSpaces.add(Double.valueOf(width));

            final Integer index = Integer.valueOf(((int)Math.round(width))/binWidth);
            Integer current = binsSpaces.get(index);
            if (current == null) {
                binsSpaces.put(index, Integer.valueOf(1));
            }
            else {
                binsSpaces.put(index, Integer.valueOf(current.intValue()+1));
            }
        }
    }

    public int getVerticalRange(int mode) {
        int result = 0;
        // TODO, check the mode value much earlier
        for (Entry<Integer, Integer> m : mode == 1 ? binsTones.entrySet() : mode == 0 ? binsSpaces.entrySet() : null) {
            result = Math.max(result, ((Integer)m.getValue()).intValue());
        }
        return result;
    }

    public int getHorisontalRange(int mode) {
        int maxBinIndex = 0;
        for (Entry<Integer, Integer> m : mode == 1 ? binsTones.entrySet() : mode == 0 ? binsSpaces.entrySet() : null) {
            maxBinIndex = Math.max(maxBinIndex, ((Integer)m.getKey()).intValue());
        }
        return maxBinIndex*binWidth;
    }
}
