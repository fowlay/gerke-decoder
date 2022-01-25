package st.foglo.gerke_decoder.detector.adaptive;

import st.foglo.gerke_decoder.GerkeDecoder;
import st.foglo.gerke_decoder.GerkeLib;
import st.foglo.gerke_decoder.GerkeLib.Debug;
import st.foglo.gerke_decoder.detector.TrigTable;
import st.foglo.gerke_decoder.wave.Wav;

final class Segment {
    
    static final double FREQ_PREC = 0.2;
    
    final CwAdaptiveImpl parent;
    final int segIndex;
    
    final Wav w;
    
    final int framesPerSlice;
    
    final int base;                // index into wave file
    final int midpoint;

    final int cohFactor;
    
    final int nofChunk;
    final int size;
    
    final double bestFrequency;
    
    final double strength;
    
    boolean isValid = false;
    
    public boolean isValid() {
        return isValid;
    }

    public void setValid(boolean isValid) {
        this.isValid = isValid;
    }

    final int clipLevel;

    public Segment(CwAdaptiveImpl parent, int segIndex,
            Wav w, int base, int framesPerSlice, int cohFactor, int nofChunk) {
        this.parent = parent;
        this.segIndex = segIndex;
        
        this.w = w;
        this.base = base;
        
        this.framesPerSlice = framesPerSlice;
        this.cohFactor = cohFactor;
        this.nofChunk = nofChunk;
        
        this.size = framesPerSlice*cohFactor*nofChunk;
        this.midpoint = base + (size % 2 == 1 ? size/2 : size/2 - 1);
        
        final double[] f = GerkeLib.getDoubleOptMulti(GerkeDecoder.O_FRANGE);
        
        // TODO, parameters: what is a reasonable setting?
        this.bestFrequency = bestFrequency(f[0], f[1], FREQ_PREC);
        
        this.strength = sumOverSegment(bestFrequency);
        
//        new Info("seg no: %d, f: %f, strength: %f", base/size, bestFrequency, strength);
        
        this.clipLevel = clipLevelInSegment();
        
        new Debug("clip level in segment: %d", clipLevel);
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
            
            // TODO, consider a trig table pool since frequencies will be reused
            final TrigTable trigTable = //new TrigTable(u, chunkSize, w.frameRate);
                    parent.getTrigTable(u);
            for (int k = 0; k < chunkSize; k++) {     // sum in chunk
                final int wIndex = base + i*chunkSize + k;
                sumSinInChunk += trigTable.sin(k)*w.wav[wIndex];
                sumCosInChunk += trigTable.cos(k)*w.wav[wIndex];
            }
            result += Math.sqrt(sumSinInChunk*sumSinInChunk + sumCosInChunk*sumCosInChunk)/chunkSize;
            
        }
        return result/nofChunk;
    }
    
    private int clipLevelInSegment() {
        
        // Heuristic parameter 50
        double clipLevelHigh = 50*strength;
        
        // Reduce cliplevel until some clipping starts to occur
        
        //final double acceptableLoss = 0.02;   // heuristic parameter, TODO
        final double acceptableLoss = 0.01;   // heuristic parameter, TODO
        
        for (double x = clipLevelHigh; true; x *= 0.7) {
            final double clipAmount = signalLossAmount(x);
            if (clipAmount > acceptableLoss) {
                for (double y = 1.05*x; true; y *= 1.05) {
                    final double clipAmountInner = signalLossAmount(y);
                    if (clipAmountInner <= acceptableLoss) {
                        new Debug("[%d] accepted clip level: % f, amount: %f", segIndex, y, clipAmountInner);
                        return (int) Math.round(y);
                    }
                    // new Debug("[%d] incr clip level: % f, amount: %f", segIndex, y, clipAmountInner);
                }
            }
            // new Debug("[%d] decr clip level: % f, amount: %f", segIndex, x, clipAmount);
        }
    }
    
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
                final int wIndex = base + k * chunkSize + i;
                final int wRaw = w.wav[wIndex];
                final int wClipped = wRaw > clipLevel ? clipLevel : wRaw < -clipLevel ? -clipLevel : wRaw;
                sumSin += trigTable.sin(i) * wRaw;
                sumCos += trigTable.cos(i) * wRaw;
                sumSinClipped += trigTable.sin(i) * wClipped;
                sumCosClipped += trigTable.cos(i) * wClipped;
            }
            sum += Math.sqrt(sumSin * sumSin + sumCos * sumCos);
            sumClipped += Math.sqrt(sumSinClipped * sumSinClipped + sumCosClipped * sumCosClipped);
        }
        return 1.0 - sumClipped / sum;
    }

    /**
     * Estimate the signal frequency, using golden-section search.
     * 
     * @param u
     * @param v
     * @param prec
     * @return
     */
    private double bestFrequency(double u, double v, double prec) {
        
        final double phi = (1 + Math.sqrt(5))/2;
        
        double u1 = u;
        double u3 = v;
        double u2 = u1 + (1/(1 + phi))*(u3-u1);
        
        double e1 = sumOverSegment(u1);
        double e3 = sumOverSegment(u3);
        double e2 = sumOverSegment(u2);
        
        double u4;
        double e4;
        
        for (int i = 0; true; i++) {
            
            if (u3-u1 < prec) {
                return (u1+u3)/2;
            }
            
            // have 3 points: u1 u2 u3

            // get 4th point, depending on positions
            
            if (u2-u1 < u3-u2) {
                u4 = u3 - (u2-u1);
                new Debug("A %d u4: %f", i, u4);
                e4 = sumOverSegment(u4);
                // points are now u1 u2 u4 u3
                if ((e1 >= e2 && e1 >= e4 && e1 >= e3) || (e2 >= e4 && e2 >= e3)) {
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
                new Debug("B %d u4: %f", i, u4);
                e4 = sumOverSegment(u4);
                // points are now u1 u4 u2 u3
                if ((e1 >= e4 && e1 >= e2 && e1 >= e3) || (e4 >= e2 && e4 >= e3)) {
                    // select left
                    u3 = u2; e3 = e2;
                    u2 = u4; e2 = e4;
                }
                else {
                    // select right
                    u1 = u4; e1 = e4;
                }
            }
        }
    }
}
