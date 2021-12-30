package st.foglo.gerke_decoder.detector.adaptive;

import java.util.LinkedList;

import st.foglo.gerke_decoder.detector.CwDetector;
import st.foglo.gerke_decoder.detector.Signal;
import st.foglo.gerke_decoder.detector.TrigTable;
import st.foglo.gerke_decoder.lib.Compute;
import st.foglo.gerke_decoder.wave.Wav;

public final class CwAdaptiveImpl implements CwDetector {
	
	final int nofSlices;
	final Wav w;
	final double tuMillis;
	
	final double tsLength;            // time slice is defined as this fraction of TU
	
	final int framesPerSlice;         // nof. frames in one slice

	final int cohFactor;              // coherence chunk size is cohFactor*framesPerSlice
	
	final int segFactor;              // segment size is segFactor*cohFactor*framesPerSlice
	
	final LinkedList<Segment> segments = new LinkedList<Segment>();
	
	public CwAdaptiveImpl(
			int nofSlices,
			Wav w,
			double tuMillis,
			int framesPerSlice,
			int cohFactor,
			int segFactor,
			double tsLength) {

		this.nofSlices = nofSlices;
		this.w = w;
		this.tuMillis = tuMillis;
		this.framesPerSlice = framesPerSlice;
		this.cohFactor = cohFactor;
		this.segFactor = segFactor;
		this.tsLength = tsLength;
	}

	@Override
	public Signal getSignal() throws InterruptedException, Exception {
		
		// analyze segments
		// TODO, already in constructor?
		
		final int segSize = segFactor*cohFactor*framesPerSlice;
		for (int i = 0;
				i + segSize - 1 < w.nofFrames;
				i += segSize) {
			
			final Segment s = new Segment(w, i, framesPerSlice, cohFactor, segFactor);
			segments.addLast(s);
		}
		
		// remove silent segments from the list
		// --- find the max strength
		// --- collect segments with strength < 0.2 max or so
		// --- remove those
		
		// compute signal
		
		
		

		final int sigSize = nofSlices;
		
		final double[] sig = new double[sigSize];
		
		for (int q = 0; q < sigSize; q++) {
			final double u = getFreq(q);
			sig[q] = getStrength(q, u);
		}
		
		
		//new GerkeLib.Death("early terminate");
		
		return new Signal(sig, 0, 0);
	}


	/**
	 * Return interpolated best frequency
	 * @param wavIndex
	 * @return
	 */
	private double getFreq(int wavIndex) {
		Segment segPrev = null;
		for (Segment seg : segments) {
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
		return segPrev.bestFrequency;
	}
	
	
	/**
	 * Get signal strength in slice q, which defines chunk q
	 */
	private double getStrength(int q, double u) {
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
		
		final int chunkSize = framesPerSlice*cohFactor;
		double sumCosInChunk = 0.0;
		double sumSinInChunk = 0.0;

		final TrigTable trigTable = new TrigTable(u, chunkSize, w.frameRate);
		for (int k = loIndex; k < hiIndex; k++) {
			final int wIndex = k;
			sumSinInChunk += trigTable.sin(k - loIndex)*w.wav[wIndex];
		    sumCosInChunk += trigTable.cos(k - loIndex)*w.wav[wIndex];
		}
		
		return Math.sqrt(sumSinInChunk*sumSinInChunk + sumCosInChunk*sumCosInChunk);
	}
}
