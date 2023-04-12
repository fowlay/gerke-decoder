package st.foglo.gerke_decoder.detector.adaptive;

import st.foglo.gerke_decoder.GerkeDecoder;
import st.foglo.gerke_decoder.GerkeLib;
import st.foglo.gerke_decoder.GerkeDecoder.HiddenOpts;
import st.foglo.gerke_decoder.GerkeLib.Debug;
import st.foglo.gerke_decoder.detector.TrigTable;
import st.foglo.gerke_decoder.wave.Wav;

final class Segment {

    static final double FREQ_PREC = 0.2;

    final CwAdaptiveImpl parent;
    final int segIndex;

    final Wav w;

    final int framesPerSlice;

    /**
     * Base and midpoint are indexes into the wav file.
     */
    final int base;
    final int midpoint;

    final int cohFactor;

    final int nofChunk;
    final int size;

    final double bestFrequency;

    final double strength;

    /**
     * This segment has significant strength, relative to
     * the total collection of segments. The value is recomputed
     * by the first call to getSignal().
     */
    boolean isValid = false;

    double slopeRight = 0.0;

    int badEstimate = 0;    // -1 if estimate is lower than actual tone, +1 if higher

    public boolean isValid() {
        return isValid;
    }

    public void setValid() {
        this.isValid = true;
    }

    final short clipLevel;

    public Segment(CwAdaptiveImpl parent, int segIndex,
            Wav w, int base, int framesPerSlice, int cohFactor, int nofChunk) {
        this.parent = parent;
        this.segIndex = segIndex;

        this.w = w;
        this.base = base;

        this.framesPerSlice = framesPerSlice;
        this.cohFactor = cohFactor;
        this.nofChunk = nofChunk;

        /**
         * Note: The size and midpoint are not valid in the case
         * of a dangling segment.
         */
        this.size = framesPerSlice*cohFactor*nofChunk;
        this.midpoint = base + (size % 2 == 1 ? size/2 : size/2 - 1);

        final double[] f = GerkeLib.getDoubleOptMulti(GerkeDecoder.O_FRANGE);

        final short maxAbsValue = maxAbsValue();
        if (maxAbsValue == 0) {
            this.bestFrequency = 0.5 * (f[0] + f[1]);
            this.strength = 0.0;
            this.clipLevel = 1;
        } else {
            this.bestFrequency = bestFrequency(this, f[0], f[1], FREQ_PREC);
            this.clipLevel = clipLevelInSegment(maxAbsValue);
            new Debug("clip level in segment: %d", clipLevel);
            this.strength = sumOverSegment(bestFrequency, this.clipLevel);
        }
    }

    /**
     * Returns the highest absolute wav value in the segment.
     */
    private short maxAbsValue() {
        short result = 0;
        for (int k = 0; k < size; k++) {
            final short value = w.wav[base + k];

            if (value > result) {
                result = value;
            }
            else if (value == Short.MIN_VALUE) {
                result = Short.MAX_VALUE;
            }
            else if (value < 0 && value < (short) (-1*(int)result)) {
                result = (short) (-1*(int)value);
            }
        }
        return result;
    }

    /**
     * Computes a clip level for the segment, depending on
     * the segment bestFrequency value and the acceptable loss
     * hidden parameter.
     */
    private short clipLevelInSegment(short maxAbsValue) {

        final double clipLevelHigh = maxAbsValue;

        // Reduce cliplevel until some clipping starts to occur

        final double acceptableLoss =
                GerkeLib.getDoubleOptMulti(
                    GerkeDecoder.O_HIDDEN)[HiddenOpts.CLIP_DEPTH.ordinal()];

        for (double x = clipLevelHigh; true; x *= 0.7) {
            final double lossAmount = signalLossAmount(x);
            if (lossAmount > acceptableLoss) {
                for (double y = 1.05*x; true; y *= 1.05) {
                    final double lossAmountInner = signalLossAmount(y);
                    if (lossAmountInner <= acceptableLoss) {
                        new Debug("[%d] accepted clip level: % f, amount: %f", segIndex, y, lossAmountInner);
                        return (short) Math.round(y);
                    }
                    // new Debug("[%d] incr clip level: % f, amount: %f", segIndex, y, clipAmountInner);
                }
            }
            // new Debug("[%d] decr clip level: % f, amount: %f", segIndex, x, clipAmount);
        }
    }

    /**
     * Returns a number that indicates relative loss of signal content
     * for a given clipping level x. The returned value is 0 for large
     * values of x, and increases towards 1 for small values of x.
     */
    private double signalLossAmount(double x) {
        final int chunkSize = framesPerSlice * cohFactor;
        final int clipLevel = (int) Math.round(x);
        double sumClipped = 0.0;
        double sum = 0.0;
        final TrigTable trigTable = parent.getTrigTable(bestFrequency);
        for (int k = 0; k < nofChunk; k++) { // iterate over chunks
            double sumSin = 0.0;
            double sumCos = 0.0;
            double sumSinClipped = 0.0;
            double sumCosClipped = 0.0;
            for (int i = 0; i < chunkSize; i++) {
                final int wIndex = base + k*chunkSize + i;
                final int wRaw = w.wav[wIndex];
                final int wClipped = wRaw > clipLevel ? clipLevel : wRaw < -clipLevel ? -clipLevel : wRaw;
                sumSin += trigTable.sin(i)*wRaw;
                sumCos += trigTable.cos(i)*wRaw;
                sumSinClipped += trigTable.sin(i)*wClipped;
                sumCosClipped += trigTable.cos(i)*wClipped;
            }
            sum += Math.sqrt(sumSin*sumSin + sumCos*sumCos);
            sumClipped += Math.sqrt(sumSinClipped*sumSinClipped + sumCosClipped*sumCosClipped);
        }
        return 1.0 - sumClipped/sum;
    }

    /**
     * Estimate the signal frequency, using golden-section search.
     *
     * @param u
     * @param v
     * @param prec
     * @return
     */
    private double bestFrequency(Segment segment, double u, double v, double prec) {

        final int zoomIn = 20;  // search for an approximate maximum over this many frequency steps

        double[] uu = new double[zoomIn + 1];
        double[] ee = new double[zoomIn + 1];

        double eMax = 0;
        int jMax = 0;
        for (int j = 0; j <= zoomIn; j++) {
            uu[j] = u + j*((v-u)/zoomIn);
            ee[j] = sumOverSegment(uu[j]);
            if (ee[j] > eMax) {
                eMax = ee[j];
                jMax = j;
            }
        }

        double u1;
        double u3;

        if (jMax == 0) {
            segment.badEstimate = +1;
            u1 = uu[jMax];
            u3 = uu[jMax+1];
        }
        else if (jMax == zoomIn) {
            segment.badEstimate = -1;
            u1 = uu[jMax-1];
            u3 = uu[jMax];
        }
        else {
            u1 = uu[jMax-1];
            u3 = uu[jMax+1];
        }

        final double phi = (1 + Math.sqrt(5))/2;

        double u2 = u1 + (1/(1 + phi))*(u3-u1);

        double e1 = sumOverSegment(u1);
        double e3 = sumOverSegment(u3);
        double e2 = sumOverSegment(u2);

        double u4;
        double e4;

        double uPrev = u3 - u1;

        for (int i = 0; true; i++) {

            if (u3-u1 < prec) {
                return (u1+u3)/2;
            }

            // have 3 points: u1 u2 u3

            // get 4th point, depending on positions

            if (u2-u1 < u3-u2) {
                u4 = u3 - (u2-u1);
                e4 = sumOverSegment(u4);
                new Debug("A %2d   %.2f(%.1f)  %.2f(%.1f)  %.2f(%.1f)", i, e1, u1, e4, u4, e3, u3);
                // points are now u1 u2 u4 u3
                if ((e2 >= e1 && e2 >= e4 && e2 >= e3) || (e1 >= e2 && e1 >= e4 && e1 >= e3)) {
                    // select left
                    u3 = u4; e3 = e4;
                }
                else {
                    // select right
                    u1 = u2; e1 = e2;
                    u2 = u4; e2 = e4;
                }
            }
            else {
                u4 = u1 + (u3-u2);
                e4 = sumOverSegment(u4);
                new Debug("B %2d   %.2f(%.1f)  %.2f(%.1f)  %.2f(%.1f)", i, e1, u1, e4, u4, e3, u3);
                // points are now u1 u4 u2 u3
                if ((e2 >= e1 && e2 >= e4 && e2 >= e3) || (e3 >= e2 && e3 >= e4 && e3 >= e1)) {
                    // select right
                    u1 = u4; e1 = e4;
                }
                else {
                    // select left
                    u3 = u2; e3 = e2;
                    u2 = u4; e2 = e4;
                }
            }
            if (!(u3-u1 < uPrev && u1 < u2 && u2 < u3)) {
                new Debug("internal confusion");
            }
        }
    }

    /**
     * Sum signal over entire segment, trying frequency u
     * @param u
     * @return
     */
    private double sumOverSegment(double u) {

        double result = 0.0;
        int chunkSize = framesPerSlice*cohFactor;

        for (int i = 0; i < size/chunkSize; i++) {    // i is a chunk index
            double sumCosInChunk = 0.0;
            double sumSinInChunk = 0.0;

            final TrigTable trigTable = parent.getTrigTable(u);
            for (int k = 0; k < chunkSize; k++) {     // sum in chunk
                final int wIndex = base + i*chunkSize + k;
                sumSinInChunk += trigTable.sin(k)*w.wav[wIndex];
                sumCosInChunk += trigTable.cos(k)*w.wav[wIndex];
            }
            result += Math.sqrt(sumSinInChunk*sumSinInChunk + sumCosInChunk*sumCosInChunk)/chunkSize;

        }
        return result/nofChunk;
    }

    /**
     * Sum clipped signal over entire segment, trying frequency u
     * @param u
     * @return
     */
    private double sumOverSegment(double u, short clipLevel) {

        double result = 0.0;
        int chunkSize = framesPerSlice*cohFactor;

        for (int i = 0; i < size/chunkSize; i++) {    // i is a chunk index
            double sumCosInChunk = 0.0;
            double sumSinInChunk = 0.0;

            final TrigTable trigTable = parent.getTrigTable(u);
            for (int k = 0; k < chunkSize; k++) {     // sum in chunk
                final int wIndex = base + i*chunkSize + k;

                final short wavValue;
                if (w.wav[wIndex] > clipLevel) {
                    wavValue = clipLevel;
                }
                else if ((int)w.wav[wIndex] < -1*((int)clipLevel)) {
                    wavValue = (short) (-1*((int)clipLevel));
                }
                else {
                    wavValue = w.wav[wIndex];
                }
                sumSinInChunk += trigTable.sin(k)*wavValue;
                sumCosInChunk += trigTable.cos(k)*wavValue;
            }
            result += Math.sqrt(sumSinInChunk*sumSinInChunk + sumCosInChunk*sumCosInChunk)/chunkSize;

        }
        return result/nofChunk;
    }
}
