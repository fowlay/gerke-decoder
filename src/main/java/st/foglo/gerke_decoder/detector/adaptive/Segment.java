package st.foglo.gerke_decoder.detector.adaptive;

import st.foglo.gerke_decoder.GerkeDecoder;
import st.foglo.gerke_decoder.GerkeLib;
import st.foglo.gerke_decoder.GerkeLib.Death;
import st.foglo.gerke_decoder.GerkeLib.Debug;

import st.foglo.gerke_decoder.detector.TrigTable;
import st.foglo.gerke_decoder.wave.Wav;

final class Segment {
	
	final CwAdaptiveImpl parent;
	
	final Wav w;
	
	final int base;
	final int midpoint;
	
	final int framesPerSlice;
	final int cohFactor;
	final int segFactor;
	
	final int size;
	
	final double bestFrequency;
	
	final double strength;
	
	final int clipLevel;

	public Segment(CwAdaptiveImpl parent,
			Wav w, int base, int framesPerSlice, int cohFactor, int segFactor) {
		this.parent = parent;
		
		this.w = w;
		this.base = base;
		
		this.framesPerSlice = framesPerSlice;
		this.cohFactor = cohFactor;
		this.segFactor = segFactor;
		
		this.size = framesPerSlice*cohFactor*segFactor;
		this.midpoint = base + (size % 2 == 1 ? size/2 : size/2 - 1);
		
		final double[] f = GerkeLib.getDoubleOptMulti(GerkeDecoder.O_FRANGE);
		
		// TODO, parameters: what is a reasonable setting?
		this.bestFrequency = bestFrequency(f[0], f[1], 8, 5);
		
		this.strength = sumOverSegment(bestFrequency);
		
//		new Info("seg no: %d, f: %f, strength: %f", base/size, bestFrequency, strength);
		
		this.clipLevel = clipLevelInSegment();
		
		new Debug("clip level in segment: %d", clipLevel);
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
				
				//new Info("k: %d, u: %f, signal: %f", k, u, e[k]);

				if (e[k] > eBest) {
					eBest = e[k];
					kBest = k;
				}
			}
			
			if (kBest == 0) {
				new Death("include lower frequencies in range");
			}
			else if (kBest == divide) {
				new Death("include higher frequencies in range");
			}
			
			// zoom in
			uFinal = u0 + kBest*uRange/divide;
			u1 = u0 + (kBest+1)*uRange/divide;
			u0 = u0 + (kBest-1)*uRange/divide;
			
			//new Info("frequency: %f", uFinal);

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
			final TrigTable trigTable = //new TrigTable(u, chunkSize, w.frameRate);
					parent.getTrigTable(u);
			for (int k = 0; k < chunkSize; k++) {     // sum in chunk
				final int wIndex = base + i*chunkSize + k;
				sumSinInChunk += trigTable.sin(k)*w.wav[wIndex];
			    sumCosInChunk += trigTable.cos(k)*w.wav[wIndex];
			}
			result += Math.sqrt(sumSinInChunk*sumSinInChunk + sumCosInChunk*sumCosInChunk)/chunkSize;
			
		}
		return result/(size/chunkSize);
	}
	
	private int clipLevelInSegment() {
		final int segIndex = base/(segFactor*framesPerSlice*cohFactor);
		
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
		for (int k = 0; k < segFactor; k++) { // iterate over chunks
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
}
