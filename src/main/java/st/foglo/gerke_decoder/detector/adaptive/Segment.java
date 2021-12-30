package st.foglo.gerke_decoder.detector.adaptive;

import st.foglo.gerke_decoder.GerkeDecoder;
import st.foglo.gerke_decoder.GerkeLib;
import st.foglo.gerke_decoder.detector.TrigTable;
import st.foglo.gerke_decoder.wave.Wav;

public final class Segment {
	
	final Wav w;
	
	final int base;
	final int midpoint;
	
	final int framesPerSlice;
	final int cohFactor;
	final int segFactor;
	
	final int size;
	
	final double bestFrequency;
	
	final double strength;

	public Segment(Wav w, int base, int framesPerSlice, int cohFactor, int segFactor) {
		this.w = w;
		this.base = base;
		
		this.framesPerSlice = framesPerSlice;
		this.cohFactor = cohFactor;
		this.segFactor = segFactor;
		
		this.size = framesPerSlice*cohFactor*segFactor;
		this.midpoint = base + (size % 2 == 1 ? size/2 : size/2 - 1);
		
		final double[] f = GerkeLib.getDoubleOptMulti(GerkeDecoder.O_FRANGE);
		this.bestFrequency = bestFrequency(f[0], f[1], 8, 5);
		
		this.strength = getStrength(bestFrequency);
		
		new GerkeLib.Info("seg no: %d, f: %f, strength: %f", base/size, bestFrequency, strength);
	}
	
	



	/**
	 * Best frequency in this segment
	 * @param f0
	 * @param f1
	 * @param divide
	 * @param laps
	 * @return
	 */
	private double bestFrequency(
			double f0,
			double f1,
			int divide,
			int laps
			) {
		
		double u0 = f0;
		double u1 = f1;
		double uFinal = -1.0;
		double[] e = new double[divide+1];
		for (int i = 0; i < laps; i++) {         // i is a laps index
			
			final double uRange = u1 - u0;
			
			// compute e for each u
			int kBest = -1;
			double eBest = -1.0;
			for (int k = 0; k <= divide; k++) {     // k is for freq stepping
				
				final double u = u0 + k*uRange/divide;
				e[k] = sumOverSegment(u);
				
				//new GerkeLib.Info("k: %d, u: %f, signal: %f", k, u, e[k]);

				if (e[k] > eBest) {
					eBest = e[k];
					kBest = k;
				}
			}
			
			if (kBest == 0) {
				new GerkeLib.Death("include lower frequencies in range");
			}
			else if (kBest == divide) {
				new GerkeLib.Death("include higher frequencies in range");
			}
			
			// zoom in
			uFinal = u0 + kBest*uRange/divide;
			u1 = u0 + (kBest+1)*uRange/divide;
			u0 = u0 + (kBest-1)*uRange/divide;
			
			//new GerkeLib.Info("frequency: %f", uFinal);

		}

		return uFinal;
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
			final TrigTable trigTable = new TrigTable(u, chunkSize, w.frameRate);
			for (int k = 0; k < chunkSize; k++) {     // sum in chunk
				final int wIndex = base + i*chunkSize + k;
				sumSinInChunk += trigTable.sin(k)*w.wav[wIndex];
			    sumCosInChunk += trigTable.cos(k)*w.wav[wIndex];
			}
			sumSinInChunk = sumSinInChunk/chunkSize;
			sumCosInChunk = sumCosInChunk/chunkSize;
			result += Math.sqrt(sumSinInChunk*sumSinInChunk + sumCosInChunk*sumCosInChunk);
			
		}
		return result;
	}
	
	private double getStrength(double u) {
		return sumOverSegment(u);
	}
}
