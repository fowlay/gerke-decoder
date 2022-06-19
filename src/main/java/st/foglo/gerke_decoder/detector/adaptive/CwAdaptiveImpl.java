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
import st.foglo.gerke_decoder.GerkeLib.Death;
import st.foglo.gerke_decoder.GerkeLib.Info;
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
    
//    final int frameRate;
//    
//    final int offsetFrames;
    
    private static final double STRENGTH_LIMIT = 0.35;

    final int cohFactor;              // coherence chunk size is cohFactor*framesPerSlice
    
    final int segFactor;              // segment size is segFactor*cohFactor*framesPerSlice
    
    final LinkedList<Segment> segments = new LinkedList<Segment>();
    
    final Map<Double, TrigTable> trigTableMap = new HashMap<Double, TrigTable>();
    final Set<Double> frequencies = new TreeSet<Double>();  // for diagnostics only
    
    final NavigableSet<Double> strengths = new TreeSet<Double>();
    
    final Map<Integer, double[]> weightTableMap = new HashMap<Integer, double[]>(); 
    
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
    }

    @Override
    public Signal getSignal() throws InterruptedException, Exception {
        
        // analyze segments
        // TODO, move this code to constructor?
        
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
        
        double strengthMax = -1.0;
        for (Segment s : segments) {
            strengthMax = Compute.dMin(strengthMax, s.strength);
        }
        for (Segment s : segments) {
            final double strength = s.strength;
            if (strength >= STRENGTH_LIMIT*strengthMax) {
                s.setValid(true);
            }
        }

        // compute signal

        final int sigSize = nofSlices;
        
        final double[] sig = new double[sigSize];

        for (int q = 0; q < sigSize; q++) {
            // q is a slice index
            final int wavIndex = q*framesPerSlice;
            final double u = getFreq(wavIndex);
            
            final int segInList = q/(framesPerSlice*cohFactor*segFactor);
            final Segment seg = segments.get(segInList);
            
            sig[q] = getStrength(q, u, seg.clipLevel);

        }

        return new Signal(sig, 0, 0);
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
        
        boolean firstLap = true;
        for (int q = 0; q < sig.length; q += cohFactor) {
            // iterate over chunks; q is slice index
            final double timeSeconds = w.secondsFromSliceIndex(q, framesPerSlice);
            if (pEnt.plotBegin <= timeSeconds && timeSeconds <= pEnt.plotEnd
                    
                    // must be wrong?????
//                    &&
//                    framesPerSlice*cohFactor + q*framesPerSlice + w.offsetFrames < w.wav.length
                    ) {

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

    /**
     * Return interpolated best frequency
     * @param wavIndex
     * @return
     */
    private double getFreq(int wavIndex) {
        Segment segPrev = null;
        for (Segment seg : segments) {
            if (seg.isValid()) {
                if (wavIndex < seg.midpoint && segPrev != null) {
                    final double uPrev = segPrev.bestFrequency;
                    return uPrev +
                            (((double) (wavIndex - segPrev.midpoint))/(seg.midpoint - segPrev.midpoint))*
                            (seg.bestFrequency - uPrev);
                }
                else if (wavIndex < seg.midpoint) {
                    return seg.bestFrequency;
                }
                else {
                    segPrev = seg;
                }
            }
        }
        return segPrev.bestFrequency;
    }
    
    
    /**
     * Get signal strength in slice q, which defines chunk q
     */
    private double getStrength(int q, double u, int clipLevel) {
        final int hiMax = nofSlices*framesPerSlice;
        final int loIndex;
        final int hiIndex;
        if (cohFactor % 2 == 0) { 
            loIndex = Compute.iMax(0, (q - cohFactor/2)*framesPerSlice);
            hiIndex = Compute.iMin(hiMax, (q + cohFactor/2)*framesPerSlice);
        }
        else {
            loIndex = Compute.iMax(0, (q - cohFactor/2)*framesPerSlice);
            hiIndex = Compute.iMin(hiMax, (q + cohFactor/2 + 1)*framesPerSlice);
        }
        
        final double sigma = GerkeLib.getDoubleOpt(GerkeDecoder.O_SIGMA);
        final int wTabSize;
        if ((hiIndex - loIndex) % 2 == 1) {
            wTabSize = (hiIndex - loIndex)/2 + 1;
        }
        else {
            wTabSize = (hiIndex - loIndex)/2;
        }
//        final double[] wTab = new double[wTabSize];
        
        final double[] wTab = getWeightTable(wTabSize, sigma);
        
        final int kMid = loIndex + (hiIndex - loIndex)/2;
        
        if ((hiIndex - loIndex) % 2 == 1) {
            for (int k = 0; k < wTabSize; k++) {
                final int d = k;
                wTab[k] = Math.exp(-Compute.squared(1000*d/(tuMillis*w.frameRate*sigma)));
            }
        }
        else {
            for (int k = 0; k < wTabSize; k++) {
                final double d = k + 0.5;
                wTab[k] = Math.exp(-Compute.squared(1000*d/(tuMillis*w.frameRate*sigma)));
            }
        }
        if (q == 100) {
            // TODO, 100 is ad-hoc
            new Info("smallest weight: %f", wTab[wTabSize-1]);            
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
            
//            if (wIndex % 24000 == 0) {
//                new GerkeLib.Info("frame value: %d", frameValueRaw);
//            }
            
            double weight;
            if ((hiIndex - loIndex) % 2 == 1) {
                weight = wTab[Compute.iAbs(k - kMid)];
            }
            else {
                try {
                    weight = k < kMid ? wTab[kMid - k - 1] : wTab[k - kMid];
                }
                catch (Exception e) {
                    new Info("odd or even: %d, points: %d", (hiIndex - loIndex) % 2, hiIndex - loIndex);
                    new Death("k: %d, kMid: %d, table; %d", k, kMid, wTab.length);
                    weight = 1.0;
                }
            }

            sumSinInChunk += weight*trigTable.sin(k - loIndex)*frameValue;
            sumCosInChunk += weight*trigTable.cos(k - loIndex)*frameValue;
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
     * Returns a weights table of given size, using the given sigma.
     * @param size
     * @param sigma
     * @return
     */
    private double[] getWeightTable(int size, double sigma) {
        final double[] wTable = weightTableMap.get(Integer.valueOf(size));
        if (wTable == null) {
            final double[] newTable = new double[size];
            for (int k = 0; k < size; k++) {
                newTable[k] = Math.exp(-Compute.squared(1000*k/(tuMillis*w.frameRate*sigma)));
            }
            weightTableMap.put(Integer.valueOf(size), newTable);
            return newTable;
        }
        else {
            return wTable;
        }
    }
    
    /**
     * Maybe not needed
     * Heuristic; unsafe because of zero divide etc
     * @param strengths
     * @return
     */
     int computeClipLevel(NavigableSet<Double> strengths) {
        final int size = strengths.size();
        final int c1 = (int) Math.round(0.92*size);
        final int c2 = (int) Math.round(0.97*size);
        int count = 0;
        double sum = 0.0;
        int nofStrength = 0;
        for (Double strength : strengths) {
            count++;
            if (count >= c2) {
                break;
            }
            else if (count >= c1) {
                sum += strength;
                nofStrength++;
            }
        }
        // Heuristic parameter 50
        double clipLevelHigh = 50*sum/nofStrength;
        
        // Reduce cliplevel until some clipping starts to occur
        
        //final double acceptableLoss = 0.02;   // heuristic parameter, TODO
        final double acceptableLoss = 0.01;   // heuristic parameter, TODO
        
        for (double x = clipLevelHigh; true; x *= 0.7) {
            final double clipAmount = signalLossAmount(x);
            if (clipAmount > acceptableLoss) {
                for (double y = 1.05*x; true; y *= 1.05) {
                    final double clipAmountInner = signalLossAmount(y);
                    if (clipAmountInner <= acceptableLoss) {
                        new GerkeLib.Debug("accepted clip level: % f, amount: %f", y, clipAmountInner);
                        return (int) Math.round(y);
                    }
                    new GerkeLib.Debug("incr clip level: % f, amount: %f", y, clipAmountInner);
                }
            }
            new GerkeLib.Debug("decr clip level: % f, amount: %f", x, clipAmount);
        }
    }

    // TODO, maybe not needed
    double clipAmount(double x) {
        int clipLevel = (int) Math.round(x);
        double sumSqClipped = 0.0;
        double sumSq = 0.0;
        
        for (int i = 0; i < w.wav.length; i++) {
            final int s = w.wav[i];
            final int sc = s > clipLevel ? clipLevel : s < -clipLevel ? -clipLevel : s; 
            sumSq += ((double) s)*s;
            sumSqClipped += ((double) sc)*sc;
        }
        return 1.0 - sumSqClipped/sumSq;
    }
    
    private double signalLossAmount(double x) {
        final int chunkSize = framesPerSlice*cohFactor;
        final int clipLevel = (int) Math.round(x);
        
        double sumClipped = 0.0;
        double sum = 0.0;
        
        for (Segment seg : segments) {
            
            final double u = seg.bestFrequency;
            final TrigTable trigTable = getTrigTable(u);
            
            for (int k = 0; k < segFactor; k++) {   // iterate over chunks
                
                double sumSin = 0.0;
                double sumCos = 0.0;
                double sumSinClipped = 0.0;
                double sumCosClipped = 0.0;

                for (int i = 0; i < chunkSize; i++) {
                    final int wIndex = seg.base + k*chunkSize + i;
                    final int wRaw = w.wav[wIndex];
                    final int wClipped = wRaw > clipLevel ? clipLevel :
                        wRaw < -clipLevel ? -clipLevel :
                            wRaw;
                    sumSin += trigTable.sin(i)*wRaw;
                    sumCos += trigTable.cos(i)*wRaw;
                    sumSinClipped += trigTable.sin(i)*wClipped;
                    sumCosClipped += trigTable.cos(i)*wClipped;
                }
                sum += Math.sqrt(sumSin*sumSin + sumCos*sumCos);
                sumClipped += Math.sqrt(sumSinClipped*sumSinClipped + sumCosClipped*sumCosClipped);
            }
        }
        return 1.0 - sumClipped/sum;
    }
    
    /**
     * For diagnostics only
     */
    public void trigTableReport() {
        new Info("nof. frequencies considered: %d", frequencies.size());
        new Info("nof. trig tables: %d", trigTableMap.size());
    }
    
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
}
