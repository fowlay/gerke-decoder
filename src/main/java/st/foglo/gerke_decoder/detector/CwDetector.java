package st.foglo.gerke_decoder.detector;

import java.io.IOException;

public interface CwDetector {
	public Signal getSignal() throws InterruptedException, Exception;

	/**
	 * Create a phase plot.
	 * 
	 * @param sig
	 * @param level
	 * @param flo
	 * @param cei
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public void phasePlot(
			double[] sig,
			double level,
			double[] flo,
			double[] cei
			) throws IOException, InterruptedException;
	
    public void frequencyStabilityPlot() throws IOException, InterruptedException;
	
	public double threshold(
			double level,
			double flo,
			double cei,
			int decoder);
}
