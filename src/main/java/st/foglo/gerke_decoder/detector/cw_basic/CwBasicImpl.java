package st.foglo.gerke_decoder.detector.cw_basic;

import java.io.IOException;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CountDownLatch;

import st.foglo.gerke_decoder.GerkeDecoder;
import st.foglo.gerke_decoder.GerkeLib;
import st.foglo.gerke_decoder.LowpassButterworth;
import st.foglo.gerke_decoder.LowpassChebyshevI;
import st.foglo.gerke_decoder.LowpassFilter;
import st.foglo.gerke_decoder.LowpassNone;
import st.foglo.gerke_decoder.LowpassTimeSliceSum;
import st.foglo.gerke_decoder.LowpassWindow;
import st.foglo.gerke_decoder.GerkeDecoder.HiddenOpts;
import st.foglo.gerke_decoder.GerkeLib.Death;
import st.foglo.gerke_decoder.GerkeLib.Debug;
import st.foglo.gerke_decoder.GerkeLib.Info;
import st.foglo.gerke_decoder.GerkeLib.Trace;
import st.foglo.gerke_decoder.GerkeLib.Warning;
import st.foglo.gerke_decoder.detector.DetectorBase;
import st.foglo.gerke_decoder.detector.Signal;
import st.foglo.gerke_decoder.detector.TrigTable;
import st.foglo.gerke_decoder.lib.Compute;
import st.foglo.gerke_decoder.plot.PlotCollector;
import st.foglo.gerke_decoder.plot.PlotCollector.Mode;
import st.foglo.gerke_decoder.plot.PlotEntries;
import st.foglo.gerke_decoder.plot.PlotEntryBase;
import st.foglo.gerke_decoder.plot.PlotEntryPhase;
import st.foglo.gerke_decoder.wave.Wav;

public class CwBasicImpl extends DetectorBase {
    
    final int decoder;
    final double threshold;
    
    final int fBest;
    
    final Signal signal;
    

    public CwBasicImpl(
            int decoder,
            double threshold,
            int nofSlices,
            Wav w,
            double tuMillis,
            int framesPerSlice,
            double tsLength,
            int fSpecified
            ) throws Exception {
        
        super(w, framesPerSlice, nofSlices, tsLength, tuMillis);
        
        this.decoder = decoder;
        this.threshold = threshold;

        if (fSpecified != -1) {
            fBest = fSpecified;
            new Info("specified frequency: %d", fBest);
            if (GerkeLib.getFlag(GerkeDecoder.O_FPLOT)) {
                new Warning("frequency plot skipped when -f option given");
            }
        }
        else {
            fBest = findFrequency();
            new Info("estimated frequency: %d", fBest);
        }
        
        this.signal = detectSignal();
    }

    @Override
    public Signal getSignal() throws Exception {
        return signal;
    }

    public Signal detectSignal() throws Exception {

        // ========================== Find clip level
        // TODO, could clipping be applied once and for all, after
        // a certain point has been passed?

        final int clipLevelOverride = GerkeLib.getIntOpt(GerkeDecoder.O_CLIPPING);

        final int clipLevel =
                clipLevelOverride != -1 ? clipLevelOverride : getClipLevel(fBest);
        new Info("clipping level: %d", clipLevel);

        final LowpassFilter filterI;
        final LowpassFilter filterQ;
        final String filterCode = GerkeLib.getOptMulti(GerkeDecoder.O_HIDDEN)[HiddenOpts.FILTER.ordinal()];
        if (filterCode.equals("b")) {
            final int order = GerkeLib.getIntOptMulti(GerkeDecoder.O_HIDDEN)[HiddenOpts.ORDER.ordinal()];
            double cutoff =
                    GerkeLib.getDoubleOptMulti(GerkeDecoder.O_HIDDEN)[HiddenOpts.CUTOFF.ordinal()];
            filterI = new LowpassButterworth(order, (double)w.frameRate, cutoff* 1000.0/tuMillis, 0.0);
            filterQ = new LowpassButterworth(order, (double)w.frameRate, cutoff* 1000.0/tuMillis, 0.0);
        }
        else if (filterCode.equals("cI")) {
            final int order = GerkeLib.getIntOptMulti(GerkeDecoder.O_HIDDEN)[HiddenOpts.ORDER.ordinal()];
            double cutoff =
                    GerkeLib.getDoubleOptMulti(GerkeDecoder.O_HIDDEN)[HiddenOpts.CUTOFF.ordinal()];
            // PARAMETER 2.0 dB, ripple
            filterI = new LowpassChebyshevI(order, (double)w.frameRate, cutoff* 1000.0/tuMillis, 1.5);
            filterQ = new LowpassChebyshevI(order, (double)w.frameRate, cutoff* 1000.0/tuMillis, 1.5);
        }
        else if (filterCode.equals("w")) {
            double cutoff =
                    GerkeLib.getDoubleOptMulti(GerkeDecoder.O_HIDDEN)[HiddenOpts.CUTOFF.ordinal()];
            filterI = new LowpassWindow(w.frameRate, cutoff* 1000.0/tuMillis);
            filterQ = new LowpassWindow(w.frameRate, cutoff* 1000.0/tuMillis);
        }
        else if (filterCode.equals("t")) {
            filterI = new LowpassTimeSliceSum(framesPerSlice);
            filterQ = new LowpassTimeSliceSum(framesPerSlice);
        }
        else if (filterCode.equals("n")) {
            filterI = new LowpassNone();
            filterQ = new LowpassNone();
        }
        else {
            new Death("no such filter supported: '%s'", filterCode);
            throw new Exception();
        }

        final double[] outSin = new double[nofSlices];
        final double[] outCos = new double[nofSlices];

        final long tBegin = System.currentTimeMillis();
        final CountDownLatch cdl = new CountDownLatch(2);
        if (GerkeLib.getIntOptMulti(GerkeDecoder.O_HIDDEN)[HiddenOpts.PHASELOCKED.ordinal()] == 1) {
            (new Thread(
                    new FilterRunnerPhaseLocked(
                            filterI,
                            w.wav,
                            outSin,
                            framesPerSlice,
                            clipLevel, fBest,
                            w.frameRate,
                            0.0,
                            cdl,
                            tsLength))).start();
        }
        else {
            (new Thread(
                    new FilterRunner(
                            filterI,
                            w.wav,
                            outSin,
                            framesPerSlice,
                            clipLevel, fBest,
                            w.frameRate,
                            0.0,
                            cdl))).start();
        }

        if (GerkeLib.getIntOptMulti(GerkeDecoder.O_HIDDEN)[HiddenOpts.PHASELOCKED.ordinal()] == 1) {
            (new Thread(
                    new FilterRunnerZero(
                            filterQ,
                            w.wav,
                            outCos,
                            framesPerSlice,
                            clipLevel, fBest,
                            w.frameRate,
                            Math.PI/2,
                            cdl))).start();
        }
        else {
            (new Thread(
                    new FilterRunner(
                            filterQ,
                            w.wav,
                            outCos,
                            framesPerSlice,
                            clipLevel, fBest,
                            w.frameRate,
                            Math.PI/2,
                            cdl))).start();
        }

        cdl.await();

        final double sigma = GerkeLib.getDoubleOpt(GerkeDecoder.O_SIGMA);
        final double eps = 0.01; // PARAMETER eps

        final int gaussSize = Compute.roundToOdd((sigma/tsLength)*Math.sqrt(-2*Math.log(eps)));
        new Debug("nof. gaussian terms: %d", gaussSize);

        final double[] ringBuffer = new double[gaussSize];
        final double[] expTable = new double[gaussSize];

        for (int j = 0; j < gaussSize; j++) {
            int m = (gaussSize-1)/2;
            expTable[j] = Math.exp(-Compute.squared((j-m)*tsLength/sigma)/2);
        }
        
        // determine array size
        
        int sigSize = 0;
        for (int q = 0; true; q++) {
            if (w.wav.length - q*framesPerSlice < framesPerSlice) {
                break;
            }
            sigSize++;
        }
        
        final double[] sig = new double[sigSize];

        for (int q = 0; true; q++) {      //  q is sig[] index

            if (w.wav.length - q*framesPerSlice < framesPerSlice) {
                break;
            }

            int ringIndex = q % gaussSize;
            ringBuffer[ringIndex] = Math.sqrt(outSin[q]*outSin[q] + outCos[q]*outCos[q]);
            int rr = ringIndex;
            double ss = 0.0;
            for (int ii = 0; ii < gaussSize; ii++) {
                ss += expTable[ii]*ringBuffer[rr];
                rr = rr+1 == gaussSize ? 0 : rr+1;
            }
            sig[q] = ss/gaussSize;
        }
        new Info("filtering took ms: %d", System.currentTimeMillis() - tBegin);
        

        final double saRaw = signalAverage(fBest, Short.MAX_VALUE);
        new Debug("signal average: %f", saRaw);
        final double sa = signalAverage(fBest, clipLevel);
        new Debug("signal average clipped: %f", sa);

        return new Signal(sig, fBest, clipLevel);
    }

    @Override
    public void phasePlot(
            double[] sig,
            double level,
            double[] flo,
            double[] cei) throws IOException, InterruptedException {
        
        final double[] cosSum = new double[nofSlices];
        final double[] sinSum = new double[nofSlices];
        final double[] wphi = new double[nofSlices];

        for (int q = 0; true; q++) {

            if (w.wav.length - q*framesPerSlice < framesPerSlice) {
                break;
            }
            
            // TODO, duplication, this code is also in DecoderBase
            final double timeSeconds = w.secondsFromSliceIndex(q, framesPerSlice);

            final double angleOffset = Compute.TWO_PI*fBest*timeSeconds;

                    double sinAcc = 0.0;
                    double cosAcc = 0.0;
                    for (int j = 0; j < framesPerSlice; j++) {
                        // j is frame index
                        final int ampRaw = w.wav[q*framesPerSlice + j];
                        final int amp = ampRaw < 0 ?
                                Compute.iMax(-signal.clipLevel, ampRaw) :
                                    Compute.iMin(signal.clipLevel, ampRaw);

                                final double angle = angleOffset + Compute.TWO_PI*fBest*j/w.frameRate;
                                sinAcc += Math.sin(angle)*amp;
                                cosAcc += Math.cos(angle)*amp;
                    }
                    cosSum[q] = cosAcc;
                    sinSum[q] = sinAcc;
        }

        for (int q = 0; q < wphi.length; q++) {
            wphi[q] = wphi(q, cosSum, sinSum, sig, level, flo, cei);
        }

        final PlotCollector pcPhase = new PlotCollector();
        
        final PlotEntries pEnt = new PlotEntries(w);
        
        for (int q = 0; q < wphi.length; q++) {
            
            final double seconds = w.secondsFromSliceIndex(q, framesPerSlice);

            if (pEnt.plotBegin <= seconds && seconds <= pEnt.plotEnd) {
                final double phase = wphi[q];
                if (phase != 0.0) {
                    pEnt.addPhase(seconds, phase, 0.0);
                }
            }  
        }
        
        for (Map.Entry<Double, List<PlotEntryBase>> e : pEnt.entries.entrySet()) {
            final PlotEntryPhase p = (PlotEntryPhase) e.getValue().get(0);
            pcPhase.ps.println(String.format("%f %f", e.getKey(), p.phase));
        }
        
        pcPhase.plot(new Mode[] {Mode.POINTS});
    }
    

    /**
     * Estimate best frequency. Optionally produce a plot.
     *
     * @param frameRate
     * @return
     * @throws IOException
     * @throws InterruptedException
     */
    private int findFrequency() throws IOException, InterruptedException {

        final int nofSubOptions = GerkeLib.getOptMultiLength(GerkeDecoder.O_FRANGE);
        if (nofSubOptions != 2) {
            new Death("expecting 2 suboptions, got: %d", nofSubOptions);

        }
        final int f0 = GerkeLib.getIntOptMulti(GerkeDecoder.O_FRANGE)[0];
        final int f1 = GerkeLib.getIntOptMulti(GerkeDecoder.O_FRANGE)[1];
        new Debug("search for frequency in range: %d to %d", f0, f1);

        final SortedMap<Integer, Double> pairs =
                GerkeLib.getFlag(GerkeDecoder.O_FPLOT) ? new TreeMap<Integer, Double>() : null;

        // search in steps of 10 Hz, PARAMETER
        final int fStepCoarse = 10;

        int fBest = -1;
        double rSquaredSumBest = -1.0;
        for (int f = f0; f <= f1; f += fStepCoarse) {
            final double rSquaredSum = r2Sum(f);
            if (pairs != null) {
                pairs.put(Integer.valueOf(f), rSquaredSum);
            }
            if (rSquaredSum > rSquaredSumBest) {
                rSquaredSumBest = rSquaredSum;
                fBest = f;
            }
        }

        // refine, steps of 1 Hz, PARAMETER
        final int fStepFine = 1;
        final int g0 = Compute.iMax(0, fBest - 18*fStepFine);
        final int g1 = fBest + 18*fStepFine;
        for (int f = g0; f <= g1; f += fStepFine) {
            final double rSquaredSum = r2Sum(f);
            if (pairs != null) {
                pairs.put(Integer.valueOf(f), rSquaredSum);
            }
            if (rSquaredSum > rSquaredSumBest) {
                rSquaredSumBest = rSquaredSum;
                fBest = f;
            }
        }

        if (fBest == g0 || fBest == g1) {
            new Warning("frequency may not be optimal, try a wider range");
        }

        if (pairs != null) {
            final PlotCollector fPlot = new PlotCollector();
            for(Entry<Integer, Double> e : pairs.entrySet()) {
                fPlot.ps.println(String.format("%d %f", e.getKey().intValue(), e.getValue()));
            }
            fPlot.plot(new Mode[] {Mode.POINTS});
        }

        return fBest;
    }
    

    /**
     * Iteratively determine a clipping level.
     */
    private int getClipLevel(int f) {

        final double delta = GerkeDecoder.P_CLIP_PREC*(1.0 - GerkeDecoder.P_CLIP_STRENGTH);

        final double uNoClip = signalAverage(f, Short.MAX_VALUE);
        new Debug("clip level: %d, signal: %f", Short.MAX_VALUE, uNoClip);

        int hi = Short.MAX_VALUE;
        int lo = 0;
        for (; true;) {

            final int midpoint = (hi + lo)/2;
            if (midpoint == lo) {
                // cannot improve more
                return hi;
            }

            double uNew = signalAverage(f, midpoint);
            new Trace("clip level: %d, signal: %f", midpoint, uNew);

            if ((1 - GerkeDecoder.P_CLIP_STRENGTH)*uNoClip > uNew && uNew > (1 - GerkeDecoder.P_CLIP_STRENGTH - delta)*uNoClip) {
                new Debug("using clip level: %d", midpoint);
                return midpoint;
            }
            else if (uNew >= (1 - GerkeDecoder.P_CLIP_STRENGTH)*uNoClip) {
                // need to clip more
                hi = midpoint;
            }
            else {
                // need to clip less
                lo = midpoint;
            }
        }
    }

    /**
     * Average signal over entire capture, at frequency f. The time slice
     * length is (framesPerSlice/frameRate)*1000 (ms). Clipping is applied.
     */
    private double signalAverage(int f, int clipLevel) {

        final TrigTable trigTable = new TrigTable(f, framesPerSlice, w.frameRate);

        double rSum = 0.0;
        int divisor = 0;
        for (int q = 0; true; q++) {
            if (w.wav.length - q*framesPerSlice < framesPerSlice) {
                break;
            }

            double sinAcc = 0.0;
            double cosAcc = 0.0;

            for (int j = 0; j < framesPerSlice; j++) {
                final int ampRaw = (int) w.wav[q*framesPerSlice + j];
                final int amp = ampRaw < 0 ? Compute.iMax(ampRaw, -clipLevel) : Compute.iMin(ampRaw, clipLevel);
                sinAcc += trigTable.sin(j)*amp;
                cosAcc += trigTable.cos(j)*amp;
            }

            // this quantity is proportional to signal amplitude in time slice
            final double r = Math.sqrt(sinAcc*sinAcc + cosAcc*cosAcc)/framesPerSlice;

            rSum += framesPerSlice*r;
            divisor += framesPerSlice;
        }

        return rSum/divisor;
    }
    
    private double r2Sum(int f) {
        double rSquaredSum = 0.0;
        final TrigTable trigTable = new TrigTable(f, framesPerSlice, w.frameRate);
        for (int q = 0; true; q++) {
            if (w.wav.length - q*framesPerSlice < framesPerSlice) {
                return rSquaredSum;
            }
            double sinAcc = 0.0;
            double cosAcc = 0.0;
            for (int j = 0; j < framesPerSlice; j++) {
                final short amp = w.wav[q*framesPerSlice + j];
                sinAcc += trigTable.sin(j)*amp;
                cosAcc += trigTable.cos(j)*amp;
            }
            final double rSquared = sinAcc*sinAcc + cosAcc*cosAcc;
            rSquaredSum += rSquared;
        }
    }
    
    /**
     * Weighted computation of phase angle. Returns 0.0 if there is no tone.
     *
     * @param k
     * @param x
     * @param y
     * @param sig
     * @return
     */
    private double wphi(
            int k,
            double[] x,  // cos-sum
            double[] y,
            double[] sig,
            double level,
            double[] flo,
            double[] cei) {

        final int len = sig.length;
        final int width = 7;

        double sumx = 0.0;
        double sumy = 0.0;
        double ampAve = 0.0;
        int m = 0;
        for (int j = Compute.iMax(0, k-width); j <= Compute.iMin(len-1, k+width); j++) {
            final double amp = sig[j];
            ampAve += amp;
            m++;
            sumx += amp*amp*x[j];
            sumy += amp*amp*y[j];
        }
        ampAve = ampAve/m;

        if (ampAve < threshold(0.2*level, flo[k], cei[k], decoder)) {
            return 0.0;
        }
        else {
            return Math.atan2(sumy, sumx);
        }
    }
}
