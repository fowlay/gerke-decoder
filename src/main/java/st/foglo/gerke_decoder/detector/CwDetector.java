package st.foglo.gerke_decoder.detector;

import java.io.IOException;

public interface CwDetector {
	public Signal getSignal() throws InterruptedException, Exception;

	public void phasePlot(
			double[] sig,
			double level,
			double levelLog,
			double[] flo,
			double[] cei,
			int ampMap
			) throws IOException, InterruptedException;
}
