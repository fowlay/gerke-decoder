package st.foglo.gerke_decoder.detector;

import java.io.IOException;

import st.foglo.gerke_decoder.wave.Wav;

public interface CwDetector {
	public Signal getSignal() throws InterruptedException, Exception;

	public void phasePlot(
			int fBest,
			int nofSlices,
			int framesPerSlice,
			Wav w,
			int clipLevel,
			double[] sig,
			double level,
			double levelLog,
			double[] flo,
			double[] cei,
			int decoder,
			int ampMap
			) throws IOException, InterruptedException;
}
