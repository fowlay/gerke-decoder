package st.foglo.gerke_decoder.detector;

import st.foglo.gerke_decoder.GerkeDecoder;
import st.foglo.gerke_decoder.wave.Wav;

public abstract class DetectorBase implements CwDetector {
	
	protected final Wav w;
	protected final int framesPerSlice; // nof. frames in one slice
	protected final int nofSlices;
	protected final double tsLength;    // time slice is defined as this fraction of TU
	protected final double tuMillis;    // length of a dot in milliseconds

	protected DetectorBase(Wav w, int framesPerSlice, int nofSlices, double tsLength, double tuMillis) {
		super();
		this.w = w;
		this.framesPerSlice = framesPerSlice;
		this.nofSlices = nofSlices;
		this.tsLength = tsLength;
		this.tuMillis = tuMillis;
	}
	
    

    /**
     * Determines threshold based on decoder.
     * 
     * TODO, duplication, this code is also in DecoderBase
     */
    public double threshold(
            double level,
            double floor,
            double ceiling,
            int decoder) {
    	
    	final double thr = GerkeDecoder.getThreshold(decoder);
        return floor + level*thr*(ceiling - floor);
    }
}
