package st.foglo.gerke_decoder.plot;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import st.foglo.gerke_decoder.GerkeLib.Debug;

/**
 * Represents a diagram to be plotted. Interface is:
 *
 * ps          - a print stream for line-by-line data
 * plot()      - create the diagram
 */
public final class PlotCollector extends CollectorBase {

    public enum Mode {
        LINES_PURPLE("lines ls 1"),
        LINES_GREEN("lines ls 2"),
        LINES_CYAN("lines ls 3"),
        LINES_GOLD("lines ls 4"),
        LINES_YELLOW("lines ls 5"),
        LINES_BLUE("lines ls 6"),
        LINES_RED("lines ls 7"),
        LINES_BLACK("lines ls 8"),
        POINTS("points");

        String s;

        Mode(String s) {
            this.s = s;
        }
    };



    public PlotCollector() throws IOException {
        super();
    }

    public void plot(Mode mode[]) throws IOException, InterruptedException {
        ps.close();
        doGnuplot(fileName, mode);
        Files.delete(
                (new File(fileNameWin != null ? fileNameWin : fileName)).toPath());
    }

    /**
     * Invoke Gnuplot
     *
     * @param tempFileName
     * @param nofCurves    1|2|3|4|5|6
     * @param mode         LINES|POINTS
     * @throws IOException
     * @throws InterruptedException
     */
    void doGnuplot(
            String tempFileName,
            Mode[] mode
            ) throws IOException, InterruptedException {
        final int nofCurves = mode.length;
        final ProcessBuilder pb =
                new ProcessBuilder(
                        isWindows() ? "gnuplot-X11" : "gnuplot",
                        "--persist",
                        "-e",
                        "set term x11 size 1400 200",
                        "-e",
                        
                        nofCurves == 6 ?
                                String.format("plot '%s' using 1:2 with %s, '%s' using 1:3 with %s, '%s' using 1:4 with %s, '%s' using 1:5 with %s, '%s' using 1:6 with %s, '%s' using 1:7 with %s",
                                        tempFileName,
                                        mode[0].s,
                                        tempFileName,
                                        mode[1].s,
                                        tempFileName,
                                        mode[2].s,
                                        tempFileName,
                                        mode[3].s,
                                        tempFileName,
                                        mode[4].s,
                                        tempFileName,
                                        mode[5].s) :
                        nofCurves == 5 ?
                                String.format("plot '%s' using 1:2 with %s, '%s' using 1:3 with %s, '%s' using 1:4 with %s, '%s' using 1:5 with %s, '%s' using 1:6 with %s",
                                        tempFileName,
                                        mode[0].s,
                                        tempFileName,
                                        mode[1].s,
                                        tempFileName,
                                        mode[2].s,
                                        tempFileName,
                                        mode[3].s,
                                        tempFileName,
                                        mode[4].s) :
                        nofCurves == 4 ?
                                String.format("plot '%s' using 1:2 with %s, '%s' using 1:3 with %s, '%s' using 1:4 with %s, '%s' using 1:5 with %s",
                                        tempFileName,
                                        mode[0].s,
                                        tempFileName,
                                        mode[1].s,
                                        tempFileName,
                                        mode[2].s,
                                        tempFileName,
                                        mode[3].s) :
                        nofCurves == 3 ?
                                String.format("plot '%s' using 1:2 with %s, '%s' using 1:3 with %s, '%s' using 1:4 with %s",
                                        tempFileName,
                                        mode[0].s,
                                        tempFileName,
                                        mode[1].s,
                                        tempFileName,
                                        mode[2].s) :
                        nofCurves == 2 ?
                                String.format("plot '%s' using 1:2 with %s, '%s' using 1:3 with %s",
                                        tempFileName,
                                        mode[0].s,
                                        tempFileName,
                                        mode[1].s) :
                                String.format("plot '%s' using 1:2 with %s",
                                        tempFileName,
                                        mode[0].s)
                        );
        pb.inheritIO();
        final Process pr = pb.start();
        final int exitCode = pr.waitFor();
        new Debug("gnuplot exited with code: %d", exitCode);
    }


}

