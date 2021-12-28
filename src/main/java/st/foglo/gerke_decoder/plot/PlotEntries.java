package st.foglo.gerke_decoder.plot;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Collection of plot entries.
 */
public class PlotEntries {
	
    public final SortedMap<Double, List<PlotEntryBase>> entries = new TreeMap<Double, List<PlotEntryBase>>();

    public void addAmplitudes(double t, double amp, double threshold, double ceiling, double floor) {
        // assumes that this always happens before decoding
        final List<PlotEntryBase> list = new ArrayList<PlotEntryBase>();
        list.add(new PlotEntrySig(amp, threshold, ceiling, floor));
        entries.put(Double.valueOf(t), list);
    }

    public void addDecoded(double t, double y) {
        final Double tBoxed = Double.valueOf(t);
        final List<PlotEntryBase> list = entries.get(tBoxed);
        if (list == null) {
            final List<PlotEntryBase> newList = new ArrayList<PlotEntryBase>();
            newList.add(new PlotEntryDecode(y));
            entries.put(tBoxed, newList);
        }
        else {
            list.add(new PlotEntryDecode(y));
        }
    }
}
