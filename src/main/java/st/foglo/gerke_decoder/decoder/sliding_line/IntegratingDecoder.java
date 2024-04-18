package st.foglo.gerke_decoder.decoder.sliding_line;

import java.util.NavigableMap;
import java.util.TreeMap;

import st.foglo.gerke_decoder.GerkeDecoder;
import st.foglo.gerke_decoder.GerkeLib;
import st.foglo.gerke_decoder.GerkeDecoder.DecoderIndex;
import st.foglo.gerke_decoder.decoder.Dash;
import st.foglo.gerke_decoder.decoder.DecoderBase;
import st.foglo.gerke_decoder.decoder.Dot;
import st.foglo.gerke_decoder.decoder.Node;
import st.foglo.gerke_decoder.decoder.ToneBase;
import st.foglo.gerke_decoder.format.Formatter;
import st.foglo.gerke_decoder.plot.HistEntries;
import st.foglo.gerke_decoder.plot.PlotEntries;
import st.foglo.gerke_decoder.wave.Wav;

public final class IntegratingDecoder extends DecoderBase {
    
    final int decoder = DecoderIndex.INTEGRATING.ordinal();
    
    /**
     * Unit is TU.
     */
    public static final double TS_LENGTH = 0.06;
    
    public static final double THRESHOLD = 0.55; // 0.524*0.9;
    
    final int sigSize;

    final double level;
    
    //int j = 0;
    final NavigableMap<Integer, ToneBase> tones = new TreeMap<Integer, ToneBase>();
    
    public IntegratingDecoder(
            
            double tuMillis,
            int framesPerSlice,
            double tsLength,
            int offset,
            Wav w,
            double[] sig,
            PlotEntries plotEntries,
            HistEntries histEntries,
            Formatter formatter,

            int sigSize,
            double[] cei,
            double[] flo,
            double level,
            double ceilingMax
            
            ) {
        
        super(
                tuMillis,
                framesPerSlice,
                tsLength,
                offset,
                w,
                sig,
                plotEntries,
                histEntries,
                formatter,
                cei,
                flo,
                ceilingMax,
                THRESHOLD
                );
        this.sigSize = sigSize;
        this.level = level;
    }
    
    private enum States {LOW, HIGH};

    

    @Override
    public void execute() throws Exception {
        // TODO Auto-generated method stub
        
        final int tsPerTu = (int)Math.round(1.0/tsLength);
        System.out.println(String.format("+++++ decoder 7, slices per TU: %d", tsPerTu));
        
        final double u = 1.0; // TODO, level, bind to a parameter
        
        
        // first pass, for normalizing
//        double strength = 0.0;
//        for (int k = tsPerTu; k < sigSize-tsPerTu; k++) {
//            
//            //System.out.println(String.format("%f", sig[k]));
//            
//            // integrate
//            double s = 0.0;
//            for (int i = -tsPerTu; i <= tsPerTu; i++) {
//                if (i == 0) {
//                    continue;
//                }
//                else if (i < 0) {
//                    s += (-1)*(sig[k+i] - (flo[k+1] + (u/1.0)*(0.5)*(cei[k+i] - flo[k+i])));
//                }
//                else {
//                    s += sig[k+i] - (flo[k+1] + (u/1.0)*(0.5)*(cei[k+i] - flo[k+i]));
//                }
//                
//            }
//            
//            strength = Math.max(strength, Math.abs(s));
//        }
        
        // TODO: s is a global maximum, maybe a localized value is better
        // 
        
        States q = States.LOW;
        double candRaise = -Double.MAX_VALUE;
        double candDrop = Double.MAX_VALUE;
        int kCandRaise = -1;
        int kCandDrop = -1;
        
        for (int k = tsPerTu; k < sigSize-tsPerTu; k++) {
            
            //System.out.println(String.format("%f", sig[k]));
            
            // integrate
            
            double localStrength = 0.0;
            
            double s = 0.0;
            for (int i = -tsPerTu; i <= (tsPerTu-1); i++) {
                if (i < 0) {
                    s += (-1)*(sig[k+i] - (flo[k+1] + (u/1.0)*(0.5)*(cei[k+i] - flo[k+i])));
                    localStrength += (-1)*(flo[k+i] - (flo[k+1] + (u/1.0)*(0.5)*(cei[k+i] - flo[k+i])));
                }
                else {
                    s += sig[k+i] - (flo[k+1] + (u/1.0)*(0.5)*(cei[k+i] - flo[k+i]));
                    localStrength += cei[k+i] - (flo[k+1] + (u/1.0)*(0.5)*(cei[k+i] - flo[k+i]));
                }
                
            }
            //System.out.println(String.format("%d: %f", k, s));
            
            // ignore candidate transitions weaker than this limit
            double ignore = 0.2;
            
            if (q == States.LOW) {
                if (s <= 0.0) {
                    continue;
                }
                else if (s >= candRaise) {
                    candRaise = s;
                    kCandRaise = k;
                }
                else if ((k - kCandRaise) <= 3) {   // parameter TODO
                    continue;
                }
                else if (candRaise/localStrength < ignore) {
                    candRaise = -Double.MAX_VALUE;
                }
                else {
                    q = States.HIGH;
                    candDrop = Double.MAX_VALUE;
                }
                
            }
            else if (q == States.HIGH) {
                if (s >= 0.0) {
                    continue;
                }
                else if (s <= candDrop) {
                    candDrop = s;
                    kCandDrop = k;
                }
                else if ((k - kCandDrop) <= 3) {   // parameter TODO
                    continue;
                }
                else if ((-candDrop)/localStrength < ignore) {
                    candDrop = Double.MAX_VALUE;
                }
                else {
                    q = States.LOW;
                    candRaise = -Double.MAX_VALUE;
                    
                    final boolean createDot =
                            (kCandDrop - kCandRaise)*tsLength <
                            GerkeDecoder.DASH_LIMIT[DecoderIndex.INTEGRATING.ordinal()];
                    
                    if (createDot) {
                        tones.put(Integer.valueOf(kCandRaise), new Dot(kCandRaise, kCandDrop));
                    }
                    else {
                        tones.put(Integer.valueOf(kCandRaise), new Dash(kCandRaise, kCandDrop));
                    }
                    
                }
            }
            
        }
        // we got tones
        System.out.println(String.format("nof. tones: %d", tones.size()));
        // duplicated code from SlidingLinePlus
        
        Node p = Node.tree;
        int qCharBegin = -999999;
        Integer prevKey = null;
        for (Integer key : tones.navigableKeySet()) {

            if (prevKey == null) {
                qCharBegin = toneBegin(key, tones);
            }

            if (prevKey == null) {
                final ToneBase tb = tones.get(key);
                p = tb instanceof Dash ? p.newNode("-") : p.newNode(".");
                lsqPlotHelper(tb);

            } else if (prevKey != null) {
                final int toneDistSlices = toneBegin(key, tones) - toneEnd(prevKey, tones);
                if (histEntries != null) {
                    histEntries.addEntry(0, toneDistSlices);
                }

                if (toneDistSlices > GerkeDecoder.WORD_SPACE_LIMIT[decoder] / tsLength) {
                    final int ts = GerkeLib.getFlag(GerkeDecoder.O_TSTAMPS)
                            ? offset + (int) Math.round(key * tsLength * tuMillis / 1000)
                            : -1;
                    formatter.add(true, p.text, ts);
                    wpm.chCus += p.nTus;
                    wpm.spCusW += 7;
                    wpm.spTicksW += toneBegin(key, tones) - toneEnd(prevKey, tones);

                    p = Node.tree;
                    final ToneBase tb = tones.get(key);
                    if (tb instanceof Dash) {
                        p = p.newNode("-");
                    } else {
                        p = p.newNode(".");
                    }
                    wpm.chTicks += toneEnd(prevKey, tones) - qCharBegin;
                    qCharBegin = toneBegin(key, tones);
                    lsqPlotHelper(tb);

                } else if (toneDistSlices > GerkeDecoder.CHAR_SPACE_LIMIT[decoder] / tsLength) {
                    formatter.add(false, p.text, -1);
                    wpm.chCus += p.nTus;
                    wpm.spCusC += 3;

                    wpm.spTicksC += toneBegin(key, tones) - toneEnd(prevKey, tones);

                    p = Node.tree;
                    final ToneBase tb = tones.get(key);
                    if (tb instanceof Dash) {
                        p = p.newNode("-");
                    } else {
                        p = p.newNode(".");
                    }
                    wpm.chTicks += toneEnd(prevKey, tones) - qCharBegin;
                    qCharBegin = toneBegin(key, tones);
                    lsqPlotHelper(tb);
                } else {
                    final ToneBase tb = tones.get(key);
                    p = tb instanceof Dash ? p.newNode("-") : p.newNode(".");
                    lsqPlotHelper(tb);
                }
            }

            prevKey = key;
        }

        if (p != Node.tree) {
            formatter.add(true, p.text, -1);
            formatter.newLine();
            wpm.chCus += p.nTus;
            wpm.chTicks += toneEnd(prevKey, tones) - qCharBegin;
        }

        wpm.report();

    }
    
    // duplicated. Open code very simple function?
    private int toneBegin(Integer key, NavigableMap<Integer, ToneBase> tones) {
        ToneBase tone = tones.get(key);
        return tone.rise;
    }

    private int toneEnd(Integer key, NavigableMap<Integer, ToneBase> tones) {
        ToneBase tone = tones.get(key);
        return tone.drop;
    }

}
