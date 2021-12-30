package st.foglo.gerke_decoder.detector.adaptive;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;

import st.foglo.gerke_decoder.GerkeLib;
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
	
	// This is a huge performance boost!
	final Map<Double, TrigTable> trigTableMap = new HashMap<Double, TrigTable>();
	
	final NavigableSet<Double> strengths = new TreeSet<Double>(); 
	
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
		// TODO, move this code to constructor?
		
		final int segSize = segFactor*cohFactor*framesPerSlice;
		for (int i = 0;
				i + segSize - 1 < w.nofFrames;
				i += segSize) {
			
			final Segment s = new Segment(this, w, i, framesPerSlice, cohFactor, segFactor);
			segments.addLast(s);
			strengths.add(s.strength);
		}
		
		// TODO
		// remove silent segments from the list
		// maybe silent segments are quite unusual?
		// --- find the max strength
		// --- collect segments with strength < 0.2 max or so
		// --- remove those
		
		// compute signal

		final int sigSize = nofSlices;
		
		final double[] sig = new double[sigSize];
		final int clipLevel = computeClipLevel(strengths);
		new GerkeLib.Info("+++ using clip level: %d", clipLevel);
		
		for (int q = 0; q < sigSize; q++) {
			final double u = getFreq(q);
			sig[q] = getStrength(q, u, clipLevel);
			
			if (q % 1000 == 999) {
				new GerkeLib.Info("sig: %f", sig[q]);
			}
		}
		
		

		double sigSum = 0.0;
		for (int i = 0; i < sigSize; i++) {
			sigSum += sig[i];
		}
		new GerkeLib.Info("+++ signal average: %f", sigSum/sigSize);
		
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
		
		double sumCosInChunk = 0.0;
		double sumSinInChunk = 0.0;

		final TrigTable trigTable = getTrigTable(u);
		for (int k = loIndex; k < hiIndex; k++) {
			final int wIndex = k;
			final int frameValueRaw = w.wav[wIndex];
			final int frameValue = frameValueRaw > clipLevel ? clipLevel :
				frameValueRaw < -clipLevel ? -clipLevel :
					frameValueRaw;
			
//			if (wIndex % 24000 == 0) {
//				new GerkeLib.Info("frame value: %d", frameValueRaw);
//			}
			
			sumSinInChunk += trigTable.sin(k - loIndex)*frameValue;
		    sumCosInChunk += trigTable.cos(k - loIndex)*frameValue;
		}
		
		return Math.sqrt(sumSinInChunk*sumSinInChunk + sumCosInChunk*sumCosInChunk)/(hiIndex - loIndex);
	}

	TrigTable getTrigTable(double u) {
		final Double uDouble = Double.valueOf(u);
		final TrigTable result = trigTableMap.get(uDouble);
		if (result == null) {
			final int chunkSize = framesPerSlice*cohFactor;
			final TrigTable trigTable = new TrigTable(u, chunkSize, w.frameRate);
			trigTableMap.put(uDouble, trigTable);
			return trigTable;
		}
		else {
			return result;
		}
	}
	
	/**
	 * Heuristic; unsafe because of zero divide etc
	 * @param strengths
	 * @return
	 */
	private int computeClipLevel(NavigableSet<Double> strengths) {
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
		return (int) Math.round(3.2*sum/nofStrength);
	}
}
