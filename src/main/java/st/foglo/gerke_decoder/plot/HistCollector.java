package st.foglo.gerke_decoder.plot;

import java.io.IOException;
import st.foglo.gerke_decoder.GerkeLib.Debug;


public final class HistCollector extends CollectorBase {

    final int binWidth;
    final int verticalRange;
    final int horisontalRange;


    public HistCollector(int binWidth, int verticalRange, int horisontalRange) throws IOException {
        super();
        this.binWidth = binWidth;
        this.verticalRange = verticalRange;
        this.horisontalRange = horisontalRange;
    }

    public void plot(int mode) throws IOException, InterruptedException {
        doGnuplot(fileName, mode);
    }

    private void doGnuplot(String tempFileName, int mode) throws IOException, InterruptedException {
        final String programName = isWindows() ? "gnuplot-X11" : "gnuplot";
        final ProcessBuilder pb =
                new ProcessBuilder(
                        programName,
                        "--persist",
                        "-e", "set term x11 size 1400 200",

                        "-e", "set style data histogram",

                        "-e", String.format("set title 'Distribution of %s lengths'",
                                mode == 1 ? "tone" : mode == 0 ? "space" : "???"),
                        "-e", "set xlabel 'ms'",

                        "-e", String.format("set style fill %s",
                                mode == 1 ? "solid 0.5" : mode == 0 ? "empty" : "???"),

                        "-e", "set style fill border lt -1",

                        "-e", String.format("set xrange [0:%d]",
                                (int)Math.round(1.15*horisontalRange)),
                        "-e", String.format("set xtics %d",
                                (int)Math.round(1.15*horisontalRange) > 1000 ? 100 : 50),
                        "-e", String.format("binwidth = %d", binWidth),
                        "-e", "set boxwidth binwidth",

                        "-e", String.format("set yrange [0:%d]",
                                (int)Math.round(1.15*verticalRange)),

                        "-e", "set tics out nomirror",

                        "-e", String.format(
                                "plot '%s' using (binwidth*floor($1/binwidth) + binwidth/2):(1.0) "+
                                        "smooth "+
                                        "freq "+
                                        "with boxes %s "+
                                        "notitle",
                                        tempFileName,
                                        mode == 1 ? "fillcolor 'magenta'" : mode == 0 ? "" : "??")
                        );
        pb.inheritIO();
        final Process pr = pb.start();
        final int exitCode = pr.waitFor();
        new Debug("gnuplot exited with code: %d", exitCode);
    }
}
