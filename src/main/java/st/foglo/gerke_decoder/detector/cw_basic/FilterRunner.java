package st.foglo.gerke_decoder.detector.cw_basic;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;

import st.foglo.gerke_decoder.LowpassFilter;
import st.foglo.gerke_decoder.GerkeDecoder;
import st.foglo.gerke_decoder.GerkeLib.Info;
import st.foglo.gerke_decoder.lib.Compute;

public final class FilterRunner extends FilterRunnerBase {

    public FilterRunner(LowpassFilter f, short[] wav, double[] out,
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

        final ArrayList<Double> sinTable = new ArrayList<Double>(); sinTable.clear();
        int tableSize = -1;
        boolean useTable = false;
        int index = -1;
        double firstAngle = -999999.9;
        boolean firstAngleDefined = false;

        for (int q = 0; true; q++) {      //  q is out[] index

            if (wav.length - q*framesPerSlice < framesPerSlice) {
                break;
            }

            // feed the filters with wav samples
            for (int k = -framesPerSlice+1; k <= 0; k++) {

                final int wavIndex = q*framesPerSlice + k;

                double outSignal = 0.0;
                if (wavIndex >= 0) {
                    int ampRaw = wav[wavIndex];   // k is non-positive!
                    final int amp = ampRaw < 0 ? Compute.iMax(-clipLevel, ampRaw) : Compute.iMin(clipLevel, ampRaw);
                    final double sinValue;
                    if (!useTable) {
                        double angle = ((freq*wavIndex)*GerkeDecoder.TWO_PI)/frameRate;
                        if (!firstAngleDefined) {
                            firstAngle = angle;
                            firstAngleDefined = true;
                            sinValue = Math.sin(angle+phaseShift);
                            index = 0;
                            sinTable.add(Double.valueOf(sinValue));
                        }
                        else {
                            double laps = (angle - firstAngle)/GerkeDecoder.TWO_PI;
                            // PARAMETER 0.005, not critical, introduces phase jitter < 0.3 degrees
                            if (laps > 0.5 && Math.abs(laps - Math.round(laps)) < 0.005) {
                                useTable = true;
                                index++;
                                tableSize = index;
                                new Info("sine table size: %d", sinTable.size());
                                sinValue = sinTable.get(0).doubleValue();
                            }
                            else {
                                sinValue = Math.sin(angle+phaseShift);
                                index++;
                                sinTable.add(Double.valueOf(sinValue));
                            }
                        }
                    }
                    else {
                        index++;
                        sinValue = sinTable.get(index % tableSize).doubleValue();
                    }
                    outSignal = f.filter(amp*sinValue);
                }

                if (k == 0) {
                    out[q] = outSignal;
                    f.reset();
                }
            }
        }
        if (cdl != null) {
            cdl.countDown();
        }
    }
}


