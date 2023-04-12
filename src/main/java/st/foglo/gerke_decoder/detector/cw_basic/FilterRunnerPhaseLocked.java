package st.foglo.gerke_decoder.detector.cw_basic;

import java.util.concurrent.CountDownLatch;

import st.foglo.gerke_decoder.GerkeLib;
import st.foglo.gerke_decoder.LowpassFilter;
import st.foglo.gerke_decoder.GerkeDecoder;
import st.foglo.gerke_decoder.GerkeDecoder.HiddenOpts;
import st.foglo.gerke_decoder.GerkeLib.Info;
import st.foglo.gerke_decoder.lib.Compute;

public final class FilterRunnerPhaseLocked extends FilterRunnerBase {

    final int nPhaseAvg;

    public FilterRunnerPhaseLocked(LowpassFilter f, short[] wav, double[] out,
            int framesPerSlice,
            int clipLevel,
            int freq,
            int frameRate,
            double phaseShift,
            CountDownLatch cdl,
            double tsLength) {
        super(f, wav, out,
                framesPerSlice,
                clipLevel,
                freq,
                frameRate,
                phaseShift,
                cdl,
                tsLength);
        nPhaseAvg = Compute.roundToOdd(GerkeLib.getDoubleOptMulti(GerkeDecoder.O_HIDDEN)[HiddenOpts.PLWIDTH.ordinal()]*(1/tsLength));
        new Info("nPhaseAvg: %d", nPhaseAvg);
    }

    @Override
    public void run() {

        for (int q = 0; true; q++) {      //  q is out[] index

            if (wav.length - q*framesPerSlice < framesPerSlice) {
                break;
            }

            final double phase =
                    smoothedPhase(nPhaseAvg, q, wav, frameRate, framesPerSlice, clipLevel, freq);

            double sum = 0.0;
            double outSignal;
            for (int k = -framesPerSlice+1; k <= 0; k++) {
                final int wavIndex = q*framesPerSlice + k;

                if (wavIndex >= 0) {
                    int ampRaw = wav[wavIndex];   // k is non-positive!
                    final int amp = ampRaw < 0 ? Compute.iMax(-clipLevel, ampRaw) : Compute.iMin(clipLevel, ampRaw);
                    final double angle = ((freq*wavIndex)*Compute.TWO_PI)/frameRate;
                    sum += Math.sin(angle - phase)*amp;
                }
                outSignal = f.filter(sum);
                if (k == 0) {
                    out[q] = outSignal;
                    f.reset();
                }
            }
        } // iterate over q

        if (cdl != null) {
            cdl.countDown();
        }
    }

    private double smoothedPhase(
            int m,
            int q,
            short[] wav,
            int frameRate,
            int framesPerSlice,
            int clipLevel,
            int freq) {


        double sinSum = 0.0;
        double cosSum = 0.0;
        // example: m = 5 =>
        // sum over j=-2, -1, 0, 1, 2
        for (int j = 0; j <= m/2; j++) {
            for (int sgn = -1; sgn <= 1; sgn += (2 + (j == 0 ? 99 : 0))) {

                for (int k = -framesPerSlice+1; k <= 0; k++) {
                    final int wavIndex = q*framesPerSlice + k + (j*sgn*framesPerSlice);

                    if (wavIndex >= 0 && wavIndex < wav.length) {
                        int ampRaw = wav[wavIndex];   // k is non-positive!
                        final int amp = ampRaw < 0 ? Compute.iMax(-clipLevel, ampRaw) : Compute.iMin(clipLevel, ampRaw);

                        final double angle = ((freq*wavIndex)*Compute.TWO_PI)/frameRate;

                        final double sinValue = Math.sin(angle+phaseShift);
                        final double cosValue = Math.cos(angle+phaseShift);

                        sinSum += sinValue*amp;
                        cosSum += cosValue*amp;
                    }
                }
            }
        }

        return Math.atan2(-cosSum, sinSum);
    }
}
