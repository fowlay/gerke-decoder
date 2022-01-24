package st.foglo.gerke_decoder.plot;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.Map.Entry;

import st.foglo.gerke_decoder.GerkeDecoder;
import st.foglo.gerke_decoder.GerkeLib;
import st.foglo.gerke_decoder.GerkeLib.Death;
import st.foglo.gerke_decoder.lib.Compute;
import st.foglo.gerke_decoder.wave.Wav;

/**
 * Collection of plot entries.
 */
public class PlotEntries {
    
    public final double plotBegin;    // beginning of plot, seconds
    public final double plotEnd;      // end of plot, seconds
    
    public final SortedMap<Double, List<PlotEntryBase>> entries = new TreeMap<Double, List<PlotEntryBase>>();

    public PlotEntries(Wav w) {

        if (GerkeLib.getOptMultiLength(GerkeDecoder.O_PLINT) != 2) {
            new Death("bad plot interval: wrong number of suboptions");
        }
        
        plotBegin = getPlotBegin(w);
        plotEnd = getPlotEnd(w);
        
        if (plotBegin >= plotEnd) {
            new Death("bad plot interval");
        }
        
    }
    
    public void addAmplitudes(
            double t,
            double amp,
            double threshold,
            double ceiling,
            double floor) {
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
    

    public void addPhase(double t, double y, double strength) {
        final Double tBoxed = Double.valueOf(t);
        final List<PlotEntryBase> list = entries.get(tBoxed);
        if (list == null) {
            final List<PlotEntryBase> newList = new ArrayList<PlotEntryBase>();
            newList.add(new PlotEntryPhase(y, strength));
            entries.put(tBoxed, newList);
        }
        else {
            list.add(new PlotEntryPhase(y, strength));
        }
    }


    public void updateAmplitudes(double tSec, double sigAvg) {
        final Double dKey = Double.valueOf(tSec);
        List<PlotEntryBase> list = entries.get(dKey);
        if (list != null) {
            // list is non-null, we are within plot limits
            List<PlotEntryBase> newList = new ArrayList<PlotEntryBase>();
            for (PlotEntryBase u : list) {
                if (u instanceof PlotEntrySig) {
                    final PlotEntrySig pes = (PlotEntrySig) u;
                    final PlotEntrySigPlus pp = new PlotEntrySigPlus(
                            pes.sig,
                            sigAvg,
                            pes.threshold,
                            pes.ceiling,
                            pes.floor);
                    newList.add(pp);
                }
                else {
                    newList.add(u);
                }
            }
            entries.put(dKey, newList);
        }
    }

    public boolean hasSigPLus() {
        for (Entry<Double, List<PlotEntryBase>> e : entries.entrySet()) {
            for (PlotEntryBase peb : e.getValue()) {
                if (peb instanceof PlotEntrySigPlus) {
                    return true;
                }
                else if (peb instanceof PlotEntrySig) {
                    continue;
                }
                else if (peb instanceof PlotEntryDecode) {
                    continue;
                }
            }
        }
        return false;
    }
    
    private static double getPlotBegin(Wav w) {
        final double offsetSec = (double) (GerkeLib.getIntOpt(GerkeDecoder.O_OFFSET));
        return Compute.dMax(GerkeLib.getDoubleOptMulti(GerkeDecoder.O_PLINT)[0], offsetSec);
    }
    
    private static double getPlotEnd(Wav w) {

        final double wavLengthSec = ((double) w.frameLength)/w.frameRate;
        final double offsetSec = (double) (GerkeLib.getIntOpt(GerkeDecoder.O_OFFSET));

        final double maxEndSec;
        if (w.length == -1) {
            // length not specified, use all of wav file
            maxEndSec = wavLengthSec;
        }
        else {
            // use specified length, but not more than file length
            maxEndSec = Compute.dMin(offsetSec + w.length, wavLengthSec);
        }

        if (GerkeLib.getDoubleOptMulti(GerkeDecoder.O_PLINT)[1] == -1.0) {
            // plot length not specified on command line
            return maxEndSec;
        }
        else {
            // length specified on command line
            return Compute.dMin(
                    maxEndSec,
                    getPlotBegin(w) + GerkeLib.getDoubleOptMulti(GerkeDecoder.O_PLINT)[1]);
        }
    }
}

