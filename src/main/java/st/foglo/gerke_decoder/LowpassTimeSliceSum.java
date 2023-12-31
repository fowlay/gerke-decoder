package st.foglo.gerke_decoder;

import st.foglo.gerke_decoder.GerkeLib.*;

/**
 * This filter is redundant. Very similar results are obtained
 * with the LowpassWindow filter with the frequency set to
 * 1.0 * (TU/samplerate).
 */
public final class LowpassTimeSliceSum implements LowpassFilter {
    
    double sum = 0.0;
    final int framesPerTimeSlice;
    int callCount = 0;

    public LowpassTimeSliceSum(int framesPerTimeSlice) {
        this.framesPerTimeSlice = framesPerTimeSlice;
        new Debug("LowpassTimeSliceSum: size: %d", this.framesPerTimeSlice);
    }

    @Override
    public double filter(double in) {
        sum += in;
        callCount++;
        return sum/callCount;
    }

    @Override
    public void reset() {
        sum = 0.0;
        callCount = 0;
    }
}
