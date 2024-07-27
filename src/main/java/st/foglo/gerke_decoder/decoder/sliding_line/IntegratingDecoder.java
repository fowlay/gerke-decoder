package st.foglo.gerke_decoder.decoder.sliding_line;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

import st.foglo.gerke_decoder.GerkeDecoder;
import st.foglo.gerke_decoder.GerkeLib;
import st.foglo.gerke_decoder.GerkeDecoder.DecoderIndex;
import st.foglo.gerke_decoder.GerkeDecoder.HiddenOpts;
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

    final NavigableMap<Integer, Dash> dashes = new TreeMap<Integer, Dash>();
    final NavigableMap<Integer, Dot> dots = new TreeMap<Integer, Dot>();
    final NavigableMap<Integer, ToneBase> tones = new TreeMap<Integer, ToneBase>();

    public IntegratingDecoder(

            double tuMillis, int framesPerSlice, double tsLength, int offset, Wav w, double[] sig,
            PlotEntries plotEntries, HistEntries histEntries, Formatter formatter,

            int sigSize, double[] cei, double[] flo, double level, double ceilingMax

    ) {

        super(tuMillis, framesPerSlice, tsLength, offset, w, sig,
                plotEntries, histEntries, formatter, cei, flo,
                ceilingMax, THRESHOLD);
        this.sigSize = sigSize;
        this.level = level;
    }

    class Candidate implements Comparable<Candidate> {

        final double strength;
        final int number;
        final double alfa;
        final int kRise;
        final int kDrop;
        final int k0;

        Candidate(double strength, double alfa, int kRise, int kDrop, int k0) {
            this.strength = strength;
            this.alfa = alfa;
            this.kRise = kRise;
            this.kDrop = kDrop;
            this.k0 = k0;
            this.number = candCount++;
        }

        @Override
        public int compareTo(Candidate o) {
            if (this.strength < o.strength) {
                return -1;
            } else if (this.strength > o.strength) {
                return 1;
            } else if (this.number < o.number) {
                return -1;
            } else {
                return 1;
            }
        }

    }

    int cmapKeyCount = 0;
    int candCount = 0;

    class CmapKey implements Comparable<CmapKey> {
        final double strength;
        final int number;

        public CmapKey(double strength) {
            this.strength = strength;
            this.number = cmapKeyCount++;
        }

        @Override
        public int compareTo(CmapKey other) {
            return this.strength < other.strength ? 1
                    : this.strength > other.strength ? -1 : this.number < other.number ? 1 : -1;
        }
    }

    class KeyIterator {

        final Iterator<Integer> iter;
        Integer pushback = null;

        public KeyIterator(Iterator<Integer> iter) {
            this.iter = iter;
        }

        boolean hasNext() {
            return pushback != null || iter.hasNext();
        }

        Integer next() {
            if (pushback != null) {
                final Integer result = pushback;
                pushback = null;
                return result;
            } else {
                return iter.next();
            }
        }

        void pushback(Integer key) {
            this.pushback = key;
        }
    }

    @Override
    public void execute() throws Exception {

        final double u = level;

        final double aMin = GerkeLib.getDoubleOptMulti(GerkeDecoder.O_HIDDEN)
                [HiddenOpts.ALFA_MIN.ordinal()];
        
        final double aMax = GerkeLib.getDoubleOptMulti(GerkeDecoder.O_HIDDEN)
                [HiddenOpts.ALFA_MAX.ordinal()];
        
        final double aDelta = GerkeLib.getDoubleOptMulti(GerkeDecoder.O_HIDDEN)
                [HiddenOpts.ALFA_STEP.ordinal()];

        final double dotStrengthLimit = GerkeLib.getDoubleOptMulti(GerkeDecoder.O_HIDDEN)
                [HiddenOpts.DOT_LIMIT.ordinal()];
        
        final double dashStrengthLimit = GerkeLib.getDoubleOptMulti(GerkeDecoder.O_HIDDEN)
                [HiddenOpts.DASH_LIMIT.ordinal()];

        final double twoDotsStrengthLimit = GerkeLib.getDoubleOptMulti(GerkeDecoder.O_HIDDEN)
                [HiddenOpts.TWO_DOTS_LIMIT.ordinal()];

        final double peaking = GerkeLib.getDoubleOptMulti(GerkeDecoder.O_HIDDEN)
                [HiddenOpts.PEAKING.ordinal()];

        // -----------------------------------------------

        final boolean isDashes = true;
        final boolean isDots = true;

        final double tsPerTu = 1.0/tsLength;

        // find dash candidates
        
        // finding dashes and finding dots can be done in parallel, but little time gained

        final Set<Candidate> cands = new HashSet<Candidate>();
        final int k0Lowest = (int) Math.round(2.0*aMax*tsPerTu) + 1;
        final int k0Highest = sigSize - (int) Math.round(2.0*aMax*tsPerTu) - 1;

        for (int k0 = k0Lowest; k0 < k0Highest; k0++) {

            double bestStrength = Double.MIN_VALUE;
            double bestA = Double.MIN_VALUE;
            int bestKRise = Integer.MIN_VALUE;
            int bestKDrop = Integer.MIN_VALUE;

            for (double a = aMin; a <= aMax; a += aDelta) {
                double sum = 0.0;
                double sumNorm = 0.0;
                final int kRise = k0 - (int) Math.round(1.5*a*tsPerTu);
                final int kDrop = k0 + (int) Math.round(1.5*a*tsPerTu);
                // 1.85 works slightly better than 2.0
                for (int k = k0 - (int) Math.round(1.85*a*tsPerTu);
                        k < k0 + (int) Math.round(1.85*a*tsPerTu);
                        k++) {
                    final double h = ((double) (k - k0))/(kDrop - k0);
                    final double g = k < kRise ? -1.0 :
                        k < kDrop ? peaking*(1.0 - 0.45*h*h*h*h) :
                            -1.0;
                    sum += g*(sig[k] - (flo[k] + u*0.5*(cei[k] - flo[k])));

                    final double norm = k < kRise ? flo[k] : k < kDrop ? cei[k] : flo[k];

                    sumNorm += g*(norm - (flo[k] + u*0.5*(cei[k] - flo[k])));
                }
                final double strength = sum/sumNorm;
                if (strength != bestStrength) {
                    bestStrength = strength;
                    bestA = a;
                    bestKRise = kRise;
                    bestKDrop = kDrop;
                }
            } // end alfa loop

            if (bestStrength >= dashStrengthLimit) {
                cands.add(new Candidate(bestStrength, bestA, bestKRise, bestKDrop, k0));
            }
        } // end loop over k

        int numPrev = Integer.MIN_VALUE;
        boolean rFlag = false;
        for (; !cands.isEmpty();) {

            final Candidate c = getStrongest(cands);

            if (numPrev != Integer.MIN_VALUE && c.number == numPrev) {
                new GerkeLib.Warning("repetition, %d %d", c.kRise, c.kDrop);
                rFlag = true;
            } else {
                numPrev = c.number;
            }

            dashes.put(Integer.valueOf(c.k0), new Dash(c.kRise, c.kDrop, c.strength));

            // drop all candidates that would overlap
            // TODO, drop some more that would be very close to overlap
            final List<Candidate> toBeRemoved = new ArrayList<Candidate>();

            for (Candidate q : cands) {
                if (q.kDrop >= c.kRise && q.kRise <= c.kDrop) {
                    toBeRemoved.add(q);
                }
            }

            for (Candidate q : toBeRemoved) {
                cands.remove(q);
            }

            if (rFlag) {
                throw new RuntimeException();
            }
        }

        cands.clear();

        // now find the dots

        final int k0LowestDots = (int) Math.round(1.0*aMax*tsPerTu) + 1;
        final int k0HighestDots = sigSize - (int) Math.round(1.0*aMax*tsPerTu) - 1;
        for (int k0 = k0LowestDots; k0 < k0HighestDots; k0++) {

            double bestStrength = Double.MIN_VALUE;
            double bestA = Double.MIN_VALUE;
            int bestKRise = Integer.MIN_VALUE;
            int bestKDrop = Integer.MIN_VALUE;

            for (double a = aMin; a <= aMax; a += aDelta) {
                double sum = 0.0;
                double sumNorm = 0.0;
                final int kRise = k0 - (int) Math.round(0.5*a*tsPerTu);
                final int kDrop = k0 + (int) Math.round(0.5*a*tsPerTu);
                for (int k = k0 - (int) Math.round(1.0*a*tsPerTu);
                        k < k0 + (int) Math.round(1.0*a*tsPerTu);
                        k++) {

                    final double g = k < kRise ? -1.0 : k < kDrop ? peaking*1.0 : -1.0;

                    sum += g*(sig[k] - (flo[k] + u*0.5*(cei[k] - flo[k])));

                    final double norm = k < kRise ? flo[k] : k < kDrop ? cei[k] : flo[k];

                    sumNorm += g*(norm - (flo[k] + u*0.5*(cei[k] - flo[k])));
                }
                final double strength = sum/sumNorm;
                if (strength != bestStrength) {
                    bestStrength = strength;
                    bestA = a;
                    bestKRise = kRise;
                    bestKDrop = kDrop;
                }
            } // end alfa loop

            if (bestStrength < dotStrengthLimit) {
                continue;
            } else {
                // candidates.add(new Candidate(bestStrength, bestA, bestKRise, bestKDrop, k0));
                // cmap.put(Double.valueOf(uniqueStrength), new Candidate(uniqueStrength, bestA,
                // bestKRise, bestKDrop, k0));
                cands.add(new Candidate(bestStrength, bestA, bestKRise, bestKDrop, k0));
            }

        } // end loop over k

        for (; !cands.isEmpty();) {

            final Candidate c = getStrongest(cands);

            dots.put(Integer.valueOf(c.k0), new Dot(c.kRise, c.kDrop, c.strength));

            final List<Candidate> toBeRemoved = new ArrayList<Candidate>();

            for (Candidate q : cands) {
                if (q.kDrop >= c.kRise && q.kRise <= c.kDrop) {
                    toBeRemoved.add(q);
                }
            }

            for (Candidate q : toBeRemoved) {
                cands.remove(q);
            }
        }

        // Merge dots and dashes

        if (!isDots) {
            dots.clear();
        }
        if (!isDashes) {
            dashes.clear();
        }

        new GerkeLib.Debug("pre-merge nof. dots: %d, dashes: %d", dots.size(), dashes.size());

        // detect clashes seen from the dots

        for (Dot dot : dots.values()) {
            for (Dash dash : dashes.values()) {
                if (isClash(dot, dash)) {
                    dot.clashers.add(dash);
                }
            }
        }

        // detect clashes seen from the dashes

        for (Dash dash : dashes.values()) {
            for (Dot dot : dots.values()) {
                if (isClash(dot, dash)) {
                    if (dot.clashers.contains(dash)) {
                        dash.clashers.add(dot);
                    }
                }
            }
        }
        
        // a dot may clash with two dashes at most; remove such dots
        
        final List<Integer> dotsToRemove = new ArrayList<Integer>();
        for (Dot dot : dots.values()) {
            if (dot.clashers.size() > 1) {
                dotsToRemove.add(Integer.valueOf(dot.k));
                for (ToneBase tbDash : dot.clashers) {
                    final Dash dash = (Dash) tbDash;
                    final Set<Dot> dotsClashingWithDash = new HashSet<Dot>();
                    for (ToneBase tbDot : dash.clashers) {
                        if ((Dot) tbDot == dot) {
                            dotsClashingWithDash.add(dot);
                        }
                    }
                    dash.clashers.removeAll(dotsClashingWithDash);
                }
            }
        }
        
        for (Integer j : dotsToRemove) {
            notNull(dots.remove(j));
        }

        // resolve clashes

        final List<Integer> dashesToRemove = new ArrayList<Integer>();
        for (Dash dash : dashes.values()) {

            if (dash.clashers.isEmpty()) {
                continue;
            } else if (dash.clashers.size() == 1) {
                notNull(dots.remove(Integer.valueOf(dash.clashers.iterator().next().k)));
            } else if (dash.clashers.size() == 2) {
                final Iterator<ToneBase> iter = dash.clashers.iterator();
                final Dot dot0 = (Dot) iter.next();
                final Dot dot1 = (Dot) iter.next();
                final double dotStrength =
                        (dot0.strength/dash.strength)*(dot1.strength/dash.strength);
                if (dotStrength > twoDotsStrengthLimit) {
                    // collect dash for removal
                    dashesToRemove.add(Integer.valueOf(dash.k));
                } else {
                    notNull(dots.remove(Integer.valueOf(dot0.k)));
                    notNull(dots.remove(Integer.valueOf(dot1.k)));
                }
            } else {
                for (ToneBase tb : dash.clashers) {
                    notNull(dots.remove(Integer.valueOf(tb.k)));
                }
            }
        }

        // remove collected dashes

        for (Integer j : dashesToRemove) {
            notNull(dashes.remove(j));
        }

        final KeyIterator dashIter = new KeyIterator(dashes.navigableKeySet().iterator());
        final KeyIterator dotsIter = new KeyIterator(dots.navigableKeySet().iterator());

        for (;;) {
            if (!dotsIter.hasNext()) {
                if (!dashIter.hasNext()) {
                    break;
                } else {
                    final Dash dash = dashes.get(dashIter.next());
                    tones.put(Integer.valueOf(dash.k), dash);
                }
            } else if (!dashIter.hasNext()) {
                final Dot dot = dots.get(dotsIter.next());
                tones.put(Integer.valueOf(dot.k), dot);
            } else { // we have a dash and at least one dot, get the keys
                final Integer dotKey = dotsIter.next();
                final Integer dashKey = dashIter.next();
                final Dot dot = dots.get(dotKey);
                final Dash dash = dashes.get(dashKey);

                if (isBefore(dot, dash)) {
                    tones.put(Integer.valueOf(dot.k), dot);
                    dashIter.pushback(dashKey);
                } else if (isAfter(dot, dash)) {
                    tones.put(Integer.valueOf(dash.k), dash);
                    dotsIter.pushback(dotKey);
                } else {
                    throw new RuntimeException("unexpected: clashing dot and dash");
                }
            }
        }

        // we have tones
        // duplicated code from SlidingLinePlus, later modified

        reportDotsAndDashes(tones);
        overlapCheck(tones);
        if (overlapCount > 0) {
            new GerkeLib.Warning("overlaps: %d", overlapCount);
        }

        Node p = Node.tree;
        int qCharBegin = -999999;
        Integer prevKey = null;
        //final double wordSpaceLimit = spExp*GerkeDecoder.WORD_SPACE_LIMIT[decoder]/tsLength;
        //final double charSpaceLimit = spExp*GerkeDecoder.CHAR_SPACE_LIMIT[decoder]/tsLength;
        
        final double wordSpIncr = 1.1;
        
        final double[] charSpLim = new double[] {-1,
                0.95*spExp*Math.sqrt(2*4)/tsLength,
                0.98*spExp*Math.sqrt(3*5)/tsLength,
                -1,
                1.03*spExp*Math.sqrt(4*6)/tsLength};
        
        final double[] wordSpLim = new double[] {-1,
                wordSpIncr*spExp*Math.sqrt(4*8)/tsLength,
                wordSpIncr*spExp*Math.sqrt(5*9)/tsLength,
                -1,
                wordSpIncr*spExp*Math.sqrt(6*10)/tsLength};
        
        for (Integer key : tones.navigableKeySet()) {

            if (prevKey == null) {
                qCharBegin = toneBegin(key, tones);
            }

            if (prevKey == null) {
                final ToneBase tb = tones.get(key);
                p = tb instanceof Dash ? p.newNode("-") : p.newNode(".");
                lsqPlotHelper(tb);

            } else if (prevKey != null) {
                final int toneDistSlices = toneCenter(key, tones) - toneCenter(prevKey, tones);
                final ToneBase prevTb = tones.get(prevKey);
                final ToneBase thisTb = tones.get(key);
                if (histEntries != null) {
                    histEntries.addEntry(0, toneDistSlices);
                }

                if (toneDistSlices > wordSpLim[prevTb.key * thisTb.key]) {
                    final int ts = GerkeLib.getFlag(GerkeDecoder.O_TSTAMPS)
                            ? offset + (int) Math.round(key*tsLength*tuMillis/1000)
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

                } else if (toneDistSlices > charSpLim[prevTb.key * thisTb.key]) {
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

    private void notNull(Object x) {
        if (x == null) {
            throw new RuntimeException();
        }
    }

    private Candidate getStrongest(Set<Candidate> cands) {
        Candidate result = null;
        for (Candidate q : cands) {
            if (result == null) {
                result = q;
            } else if (q.compareTo(result) == -1) {
                continue;
            } else {
                result = q;
            }
        }
        return result;
    }

    /**
     * Returns true if the dot is entirely after the dash.
     */
    private boolean isAfter(Dot dot, Dash dash) {
        return dot.rise > dash.drop;
    }

    /**
     * Returns true if the dot is entirely before the dash.
     */
    private boolean isBefore(Dot dot, Dash dash) {
        return dot.drop < dash.rise;
    }

    private boolean isClash(Dot dot, Dash dash) {
        return !isBefore(dot, dash) && !isAfter(dot, dash);
    }

    private int overlapCount = 0;

    private void overlapCheck(NavigableMap<Integer, ToneBase> tones) {

        ToneBase prev = null;
        for (Integer key : tones.navigableKeySet()) {
            final ToneBase tb = tones.get(key);
            if (prev == null) {
                prev = tb;
                continue;
            } else if (prev.drop < tb.rise) {
                prev = tb;
                continue;
            } else {
                final String prevType = prev instanceof Dash ? "dash" : "dot";
                final String type = tb instanceof Dash ? "dash" : "dot";
                new GerkeLib.Warning("overlapping tones, %s drop: %d, %s rise: %d",
                        prevType, prev.drop, type, tb.rise);
                overlapCount++;
                prev = tb;
            }
        }
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
    
    private int toneCenter(Integer key, NavigableMap<Integer, ToneBase> tones) {
        ToneBase tone = tones.get(key);
        return tone.k;
    }

}
