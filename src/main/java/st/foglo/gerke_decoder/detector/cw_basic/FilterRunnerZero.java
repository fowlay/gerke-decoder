package st.foglo.gerke_decoder.detector.cw_basic;

import java.util.concurrent.CountDownLatch;

import st.foglo.gerke_decoder.LowpassFilter;

public final class FilterRunnerZero extends FilterRunnerBase {

    public FilterRunnerZero(LowpassFilter f, short[] wav, double[] out,
            int framesPerSlice,
            int clipLevel,
            int freq,
            int frameRate,
            double phaseShift,
            CountDownLatch cdl) {
        super(f, wav, out,
                framesPerSlice,
                clipLevel,
                freq,
                frameRate,
                phaseShift,
                cdl,
                999.999);
    }

    @Override
    public void run() {

        for (int q = 0; true; q++) {      //  q is out[] index

            if (wav.length - q*framesPerSlice < framesPerSlice) {
                break;
            }
            out[q] = 0.0;
        }

        if (cdl != null) {
            cdl.countDown();
        }
    }
}
