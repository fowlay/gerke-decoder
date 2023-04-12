package st.foglo.gerke_decoder.detector.adaptive;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

import st.foglo.gerke_decoder.GerkeDecoder;
import st.foglo.gerke_decoder.GerkeLib;
import st.foglo.gerke_decoder.GerkeLib.Info;
import st.foglo.gerke_decoder.GerkeLib.Warning;
import st.foglo.gerke_decoder.detector.DetectorBase;
import st.foglo.gerke_decoder.detector.Signal;
import st.foglo.gerke_decoder.detector.TrigTable;
import st.foglo.gerke_decoder.lib.Compute;
import st.foglo.gerke_decoder.plot.PlotCollector;
import st.foglo.gerke_decoder.plot.PlotEntries;
import st.foglo.gerke_decoder.plot.PlotEntryBase;
import st.foglo.gerke_decoder.plot.PlotEntryFreq;
import st.foglo.gerke_decoder.plot.PlotEntryPhase;
import st.foglo.gerke_decoder.plot.PlotCollector.Mode;
import st.foglo.gerke_decoder.wave.Wav;

public final class CwAdaptiveImpl extends DetectorBase {

    private static final double STRENGTH_LIMIT = 0.35;

    final int cohFactor;              // coherence chunk size is cohFactor*framesPerSlice

    final int segFactor;              // segment size is segFactor*cohFactor*framesPerSlice

    final LinkedList<Segment> segments = new LinkedList<Segment>();

    final Map<Double, TrigTable> trigTableMap = new HashMap<Double, TrigTable>();
    final Set<Double> frequencies = new TreeSet<Double>();  // for diagnostics only

    final NavigableSet<Double> strengths = new TreeSet<Double>();

    final Map<Integer, double[]> weightTableMap = new HashMap<Integer, double[]>();

    final double strengthMax;

    public CwAdaptiveImpl(
            int nofSlices,
            Wav w,
            double tuMillis,
            int framesPerSlice,
            int cohFactor,
            int segFactor,
            double tsLength) {

        super(w, framesPerSlice, nofSlices, tsLength, tuMillis);

        this.cohFactor = cohFactor;
        this.segFactor = segFactor;

        new Info("coherence factor: %d", cohFactor);
        new Info("segment factor: %d", segFactor);

        // analyze segments
        final int segSize = segFactor*cohFactor*framesPerSlice;
        int base = 0;
        int segIndex = 0;
        for (; ; base += segSize) {
            if (base + segSize > w.nofFrames) {
                break;
            }
            final Segment s = new Segment(this, segIndex, w, base, framesPerSlice, cohFactor, segFactor);
            segIndex++;
            segments.addLast(s);
            strengths.add(s.strength);
        }

        if (w.nofFrames - base >= framesPerSlice*cohFactor) {
            // create one dangling segment
            final int nofChunk = (w.nofFrames - base)/(cohFactor*framesPerSlice);
            final Segment s = new Segment(this, segIndex, w, base, framesPerSlice, cohFactor, nofChunk);
            segments.addLast(s);
            strengths.add(s.strength);
        }

        new Info("nof. segments: %d", segments.size());

        // warn if actual CW tone frequency seems to be outside estimate
        int estimateIsLower = 0;
        int estimateIsHigher = 0;
        for (Segment s : segments) {
            if (s.badEstimate == +1) {
                ++estimateIsHigher;
            }
            else if (s.badEstimate == -1) {
                ++estimateIsLower;
            }
        }
        final double[] f = GerkeLib.getDoubleOptMulti(GerkeDecoder.O_FRANGE);
        if (4*estimateIsHigher > segments.size()) {
            new Warning("CW tone frequency may be lower than used estimate (%.0f..%.0f Hz)", f[0], f[1]);
        }
        else if (4*estimateIsLower > segments.size()) {
            new Warning("CW tone frequency may be higher than used estimate (%.0f..%.0f Hz)", f[0], f[1]);
        }

        double strengthMax = -1.0;
        for (Segment s : segments) {
            // strengthMax = Compute.dMin(strengthMax, s.strength);
            strengthMax = Compute.dMax(strengthMax, s.strength);
        }
        this.strengthMax = strengthMax;

        for (Segment s : segments) {
            final double strength = s.strength;
            if (strength >= STRENGTH_LIMIT*strengthMax) {
                s.setValid();
            }
        }

        // prepare for linear interpolation
        Segment prevValid = null;
        for (Segment s : segments) {
            if (s.isValid && prevValid != null) {
                final double slope =
                        (s.bestFrequency - prevValid.bestFrequency)/(s.midpoint - prevValid.midpoint);
                prevValid.slopeRight = slope;
            }
            if (s.isValid) {
                prevValid = s;
            }
        }
    }

    @Override
    public Signal getSignal() throws InterruptedException, Exception {

        // compute signal

        final double[] sig = new double[nofSlices];

        // iterate over slices
        for (int q = 0; q < nofSlices; q++) {
            final int wavIndex = q*framesPerSlice;
            final double freq = getFreq(wavIndex);
            final int segIndex = wavIndex/(segFactor*cohFactor*framesPerSlice);
            final Segment seg = segments.get(segIndex);
            sig[q] = getStrength(q, freq, seg.clipLevel);
        }

        return new Signal(sig, 0, 0);
    }

    /**
     * Return interpolated best frequency
     * @param wavIndex
     * @return
     */
    private double getFreq(int wavIndex) {

        int segIndex = wavIndex/(segFactor*cohFactor*framesPerSlice);

        Segment s = segments.get(segIndex);

        Segment prev = null;
        for (int p = segIndex-1; p >= 0; p--) {
            prev = segments.get(p);
            if (prev.isValid) {
                break;
            }
            else if (p == 0) {
                prev = null;
            }
        }

        // interpolate, with special care about beginning and end
        if (prev != null && (!s.isValid || s.isValid && wavIndex < s.midpoint)) {
            return prev.bestFrequency + prev.slopeRight*(wavIndex - prev.midpoint);
        }
        else if (s.isValid && wavIndex >= s.midpoint) {
            return s.bestFrequency + s.slopeRight*(wavIndex - s.midpoint);
        }
        else {
            for (int p = segIndex; true; p++) {
                final Segment nextValid = segments.get(p);
                if (nextValid.isValid) {
                    return nextValid.bestFrequency;
                }
            }
        }
    }

    /**
     * Get smoothed signal strength in slice q
     */
    private double getStrength(
            int q,                          // slice index
            double u,                       // frequency
            int clipLevel)                  // clip level
    {
        final int hiMax = nofSlices*framesPerSlice;  // hiMax is equal to, or less than, wav array size
        final int loIndex;
        final int hiIndex;

        if (cohFactor % 2 == 0) {
            // Coherence factor is even
            hiIndex = Compute.iMin(hiMax, (q + cohFactor/2)*framesPerSlice);
        }
        else {
            // Coherence factor is odd
            hiIndex = Compute.iMin(hiMax, (q + cohFactor/2 + 1)*framesPerSlice);
        }
        loIndex = Compute.iMax(0, (q - cohFactor/2)*framesPerSlice);

        final double[] wTab = getWeightTable(loIndex, hiIndex);
        final int wTabSize = wTab.length;

        final int kMid = loIndex + (hiIndex-loIndex)/2;

        if (q == nofSlices/2) {
            new Info("smallest weight in Gaussian smoothing: %f", wTab[wTabSize-1]);
        }

        double sumCosInChunk = 0.0;
        double sumSinInChunk = 0.0;
        double sumWeight = 0.0;

        final TrigTable trigTable = getTrigTable(u);

        for (int k = loIndex; k < hiIndex; k++) {
            final int frameValueRaw = w.wav[k];
            final int frameValue = frameValueRaw > clipLevel ? clipLevel :
                frameValueRaw < -clipLevel ? -clipLevel :
                    frameValueRaw;

            double weight;
            if ((hiIndex - loIndex) % 2 == 1) {
                weight = wTab[Compute.iAbs(k - kMid)];
            }
            else {
                weight = k < kMid ? wTab[kMid - k - 1] : wTab[k - kMid];
            }

            sumSinInChunk += weight*trigTable.sin(k-loIndex)*frameValue;
            sumCosInChunk += weight*trigTable.cos(k-loIndex)*frameValue;
            sumWeight += weight;
        }

        return Math.sqrt(sumSinInChunk*sumSinInChunk + sumCosInChunk*sumCosInChunk)/sumWeight;
    }

    TrigTable getTrigTable(double u) {

        final Double uDouble = Double.valueOf(u);
        frequencies.add(uDouble);

        // maximum phase fault 0.2 radians
        final double freqStep = 0.2 / (Compute.TWO_PI*((double)framesPerSlice*cohFactor/w.frameRate));

        final long nSteps = Math.round(u/freqStep);

        final Double uIntegral = nSteps*freqStep;

        final TrigTable result = trigTableMap.get(uIntegral);
        if (result == null) {
            final int chunkSize = framesPerSlice*cohFactor;
            final TrigTable trigTable = new TrigTable(u, chunkSize, w.frameRate);
            trigTableMap.put(uIntegral, trigTable);
            return trigTable;
        }
        else {
            return result;
        }
    }

    /**
     * Returns a weights table. Computed tables are cached.
     */
    private double[] getWeightTable(int loIndex, int hiIndex) {

        final double[] table = weightTableMap.get(Integer.valueOf(hiIndex-loIndex));

        if (table != null) {
            return table;
        }
        else {
            final int wTabSize;
            if ((hiIndex-loIndex) % 2 == 0) {
                wTabSize = (hiIndex-loIndex)/2;
            }
            else {
                wTabSize = (hiIndex-loIndex)/2 + 1;
            }

            final double[] newTable = new double[wTabSize];

            final double sigma = GerkeLib.getDoubleOpt(GerkeDecoder.O_SIGMA);

            if ((hiIndex-loIndex) % 2 == 0) {
                for (int k = 0; k < wTabSize; k++) {
                    final double d = k + 0.5;
                    newTable[k] = Math.exp(-Compute.squared(1000*d/(tuMillis*w.frameRate*sigma)));
                }
            }
            else {
                for (int k = 0; k < wTabSize; k++) {
                    final int d = k;
                    newTable[k] = Math.exp(-Compute.squared(1000*d/(tuMillis*w.frameRate*sigma)));
                }
            }

            weightTableMap.put(Integer.valueOf(hiIndex-loIndex), newTable);
            return newTable;
        }
    }

    /**
     * For diagnostics only
     */
    public void trigTableReport() {
        new Info("nof. frequencies considered: %d", frequencies.size());
        new Info("nof. trig tables: %d", trigTableMap.size());
    }

    @Override
    public void frequencyStabilityPlot() throws IOException, InterruptedException {

        final PlotEntries pEnt = new PlotEntries(w);

        final PlotCollector pc = new PlotCollector();

        for (int t = 0; true; t++) {
            // stepping is one second

            if (t > pEnt.plotEnd) {
                break;
            }
            else if (t >= pEnt.plotBegin && t <= pEnt.plotEnd) {
                int wavIndex = w.wavIndexFromSeconds(t);
                if (wavIndex >= w.wav.length) {
                    break;
                }
                double frequency = getFreq(wavIndex);

                pEnt.addFrequency(t, frequency);

                //new Info("time: %d, freq: %f", t, frequency);
            }
        }

        for (Map.Entry<Double, List<PlotEntryBase>> e : pEnt.entries.entrySet()) {
            pc.ps.println(
                    String.format("%d %f",
                            (int) Math.round(e.getKey()),
                            ((PlotEntryFreq)e.getValue().get(0)).freq));

        }

        pc.plot(new Mode[]{Mode.LINES_PURPLE});
    }

    @Override
    public void phasePlot(
            double[] sig,
            double level,
            double[] flo,
            double[] cei) throws IOException, InterruptedException {

        final PlotEntries pEnt = new PlotEntries(w);

        double angleOffsetPrev = 0.0;
        double angleOffset = 0.0;

        double tPrev = 0.0;

        // iterate over chunks; q is slice index
        boolean firstLap = true;
        for (int q = 0; q < sig.length; q += cohFactor) {
            final double timeSeconds = w.secondsFromSliceIndex(q, framesPerSlice);
            if (pEnt.plotBegin <= timeSeconds && timeSeconds <= pEnt.plotEnd) {

                if (firstLap) {
                    tPrev = timeSeconds;
                    angleOffsetPrev = 0.0;
                    firstLap = false;
                }
                else {
                    final double f = getFreq(q*framesPerSlice);

                    angleOffset = angleOffsetPrev + Compute.TWO_PI*f*(timeSeconds - tPrev);
                    angleOffset -= Math.round(angleOffset/Compute.TWO_PI)*Compute.TWO_PI;
                    if (angleOffset < 0.0) {
                        angleOffset += Compute.TWO_PI;
                    }

                    // iterate over current chunk
                    double sumSin = 0.0;
                    double sumCos = 0.0;
                    for (int k = 0; k < framesPerSlice*cohFactor; k++) {
                        final int wavIndex = k + q*framesPerSlice;
                        final double tIncr = ((double) k)/w.frameRate;
                        sumSin += Math.sin(angleOffset + Compute.TWO_PI*f*tIncr)*w.wav[wavIndex];
                        sumCos += Math.cos(angleOffset + Compute.TWO_PI*f*tIncr)*w.wav[wavIndex];
                    }
                    final double p = Math.atan2(sumSin, sumCos);

                    final double amp = Math.sqrt(Compute.squared(sumSin) + Compute.squared(sumCos));

                    if (amp >= flo[q] + 0.7*(cei[q] - flo[q])) {
                        pEnt.addPhase(timeSeconds, p, amp);
                    }

                    angleOffsetPrev = angleOffset;
                    tPrev = timeSeconds;
                }
            }
        }

        final PlotCollector pc = new PlotCollector();

        for (Map.Entry<Double, List<PlotEntryBase>> e : pEnt.entries.entrySet()) {
            final PlotEntryPhase p = (PlotEntryPhase) e.getValue().get(0);
            pc.ps.println(String.format("%f %f", e.getKey(), p.phase));
        }

        pc.plot(new Mode[] {Mode.POINTS});
    }

}
