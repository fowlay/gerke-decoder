package st.foglo.gerke_decoder.plot;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;

public class CollectorBase {

    protected final String fileName;
    protected final String fileNameWin;
    public final PrintStream ps;
    
    
    
    public CollectorBase() throws IOException {
        if (isWindows()) { 
            this.fileName = makeTempFile();
            this.fileNameWin = toWindows(this.fileName);
            this.ps = new PrintStream(new File(fileNameWin));
        }
        else {
            this.fileName = makeTempFile();
            this.fileNameWin = null;
            this.ps = new PrintStream(new File(fileName));
        }
    }
    
    
    String toWindows(String tempFileName) throws IOException {
        final ProcessBuilder pb = new ProcessBuilder("cygpath", "-w", tempFileName);
        final Process pr = pb.start();
        final InputStream is = pr.getInputStream();
        for (StringBuilder sb = new StringBuilder(); true; ) {
            final int by = is.read();
            if (by == -1) {
                return sb.toString();
            }
            else {
                final char c = (char)by;
                if (c != '\r' && c != '\n') {
                    sb.append(c);
                }
            }
        }
    }


    /**
     * Creates an empty temp file with default naming, e.g. /tmp/tmp.zfiRl90xuA
     * The fully qualified file name is returned.
     */
    String makeTempFile() throws IOException {
        final ProcessBuilder pb = new ProcessBuilder("mktemp");
        final Process pr = pb.start();
        final InputStream is = pr.getInputStream();
        for (StringBuilder sb = new StringBuilder(); true; ) {
            final int by = is.read();
            if (by == -1) {
                return sb.toString();
            }
            else {
                final char c = (char)by;
                if (c != '\r' && c != '\n') {
                    sb.append(c);
                }
            }
        }
    }

    boolean isWindows() {
        return System.getProperty("os.name").startsWith("Windows");
    }
}
