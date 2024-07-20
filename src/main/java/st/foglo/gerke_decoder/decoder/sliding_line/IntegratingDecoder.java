package st.foglo.gerke_decoder.decoder.sliding_line;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableMap;
import java.util.SortedMap;
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
    final NavigableMap<Integer, Dash> dashes = new TreeMap<Integer, Dash>();
    final NavigableMap<Integer, Dot> dots = new TreeMap<Integer, Dot>();
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
    

    class Candidate implements Comparable<Candidate> {
    	
    	final double strength;
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
    	}

		@Override
		public int compareTo(Candidate o) {
			if (this.strength < o.strength) {
				return 1;			
			}
			else if (this.strength == o.strength) {
				return 0;
			}
			else {
				return -1;
			}
		}
    	
    }
    
    
    int cmapKeyCount = 0;
    
    class CmapKey implements Comparable<CmapKey> {
		final double strength;
    	final int number;
    	
    	public CmapKey(double strength) {
			this.strength = strength;
			this.number = cmapKeyCount++;
		}
    	
		@Override
		public int compareTo(CmapKey other) {
			return this.strength < other.strength ? -1 :
				this.strength > other.strength ? 1 :
					this.number < other.number ? -1 :
						1;
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
    		}
    		else {
    			return iter.next();
    		}
    	}
    	
    	void pushback(Integer key) {
    		this.pushback = key;
    	}
    }

    @Override
    public void execute() throws Exception {
        
        final double tsPerTu = 1.0/tsLength;
        // new GerkeLib.Info("decoder 7, slices per TU: %f", tsPerTu);
        
        final double u = level; // PARA, settable from CLI
        
        final double aMax = 1.10; // PARA .. +- 10% seems ok, more than that causes timing issues
        final double aMin = 0.9; // PARA
        final double aDelta = 0.02; // PARA ... should have little effect, but this is not the case??
        
        final double dashStrengthLimit = 0.3; // PARA
        final double dotStrengthLimit = 0.3; // PARA
        
        final double twoDotsStrengthLimit = 1.0; // PARA .. dot doublets <------|-----> dashes
        
        final double peaking = 1.3;
        
        final boolean isDashes = true;
        final boolean isDots = true;
        
        // find dashes
        
        final int k0Lowest = (int)Math.round(2.0*aMax*tsPerTu) + 1;
        final int k0Highest = sigSize - (int)Math.round(2.0*aMax*tsPerTu) - 1;
       
        //final SortedSet<Candidate> candidates = new TreeSet<Candidate>();
        
        final SortedMap<CmapKey, Candidate> cmap = new TreeMap<CmapKey, Candidate>();
        
        new GerkeLib.Info("find dash candidates");
        for (int k0 = k0Lowest; k0 < k0Highest; k0++) {  // new GerkeLib.Info("k0: %d", k0);
        	
        	double bestStrength = Double.MIN_VALUE;
        	double bestA = Double.MIN_VALUE;
        	int bestKRise = Integer.MIN_VALUE;
        	int bestKDrop = Integer.MIN_VALUE;
        	
        	for (double a = aMin; a <= aMax; a += aDelta) {
        		double sum = 0.0;
        		double sumNorm = 0.0;
        		final int kRise = k0 - (int)Math.round(1.5*a*tsPerTu);
        		final int kDrop = k0 + (int)Math.round(1.5*a*tsPerTu);
        		for (int k = k0 - (int)Math.round(2.0*a*tsPerTu);
        				k < k0 + (int)Math.round(2.0*a*tsPerTu);
        				k++) {
        			final double g = k < kRise ? -1.0 : k < kDrop ? peaking*1.0 : -1.0;
        			sum += g*(sig[k] - (flo[k] + u*0.5*(cei[k] - flo[k])));
        			
        			final double norm = k < kRise ? flo[k] :
        				k < kDrop ? cei[k] :
        					flo[k];
        			
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

    		if (bestStrength < dashStrengthLimit) {
    			continue;
    		}
    		else {
    			// candidates.add(new Candidate(bestStrength, bestA, bestKRise, bestKDrop, k0));
    			// final double uniqueStrength = uniqueStrength(bestStrength, cmap);
    			//cmap.put(Double.valueOf(uniqueStrength), new Candidate(uniqueStrength, bestA, bestKRise, bestKDrop, k0));
    			
    			//cmapPut(bestStrength, new Candidate(bestStrength, bestA, bestKRise, bestKDrop, k0), cmap);
    			cmap.put(new CmapKey(bestStrength), new Candidate(bestStrength, bestA, bestKRise, bestKDrop, k0));
    		}
        } // end loop over k

        // loop over dash candidates, strongest first
        new GerkeLib.Info("find dashes");
        
        new GerkeLib.Info("keyset size: %d", cmap.keySet().size());
        for (CmapKey k : cmap.keySet()) {
        	
        	new GerkeLib.Info("candidate k: %d", cmap.get(k).k0);
        	
        }
        
        
        Candidate cPrev = null;
        boolean rFlag = false;
        for (; 
        		//!candidates.isEmpty();
        		!cmap.isEmpty();
        		) {
        	
        	final Candidate c = cmap.get(cmap.lastKey());
        			// cmap.get(cmap.lastKey());
        			// candidates.first();
        			//candidates.iterator().next();
        	if (c == cPrev) {
        		new GerkeLib.Info("repetition, %d %d", c.kRise, c.kDrop);
        		rFlag = true;
        	}
        	else {
        		cPrev = c;
        	}

        	// new GerkeLib.Info("strength: %e", c.strength);
        	dashes.put(Integer.valueOf(c.k0), new Dash(c.kRise, c.kDrop, c.strength));

        	// drop all candidates that would overlap
        	// TODO, drop some more that would be very close to overlap
        	final List<CmapKey> toBeRemoved = new ArrayList<CmapKey>();
        	for (CmapKey k : cmap.keySet()) {
        		Candidate q = cmap.get(k);
        		if (q.kDrop >= c.kRise && q.kRise <= c.kDrop) {
        			toBeRemoved.add(k);
        		}
        	}
        	//new GerkeLib.Info("TBR size: %d", toBeRemoved.size());
        	//candidates.removeAll(toBeRemoved);
        	
//        	for (Candidate z : toBeRemoved) {
//        		if (!candidates.contains(z)) {
//        			throw new RuntimeException("contains failed ++++++++");
//        		}
//        		if (!candidates.remove(z)) {
//        			throw new RuntimeException("remove failed ++++++++");
//        		}
//        	}
//        	
        	
        	for (CmapKey k : toBeRemoved) {
        		cmap.remove(k);
        	}
        	// new GerkeLib.Info("candidates size: %d", candidates.size());
        	if (rFlag) { throw new RuntimeException(); }
        }
                
        //candidates.clear();
        cmap.clear();
        
        // now find the dots
        
        final int k0LowestDots = (int)Math.round(1.0*aMax*tsPerTu) + 1;
        final int k0HighestDots = sigSize - (int)Math.round(1.0*aMax*tsPerTu) - 1;
        new GerkeLib.Info("find dot candidates");
        for (int k0 = k0LowestDots; k0 < k0HighestDots; k0++) {
        	
        	double bestStrength = Double.MIN_VALUE;
        	double bestA = Double.MIN_VALUE;
        	int bestKRise = Integer.MIN_VALUE;
        	int bestKDrop = Integer.MIN_VALUE;
        	
        	for (double a = aMin; a <= aMax; a += aDelta) {
        		double sum = 0.0;
        		double sumNorm = 0.0;
        		final int kRise = k0 - (int)Math.round(0.5*a*tsPerTu);
        		final int kDrop = k0 + (int)Math.round(0.5*a*tsPerTu);
        		for (int k = k0 - (int)Math.round(1.0*a*tsPerTu);
        				k < k0 + (int)Math.round(1.0*a*tsPerTu);
        				k++) {
        			
        			final double g = k < kRise ? -1.0 : k < kDrop  ? peaking*1.0 : -1.0;
        			
        			sum += g*(sig[k] - (flo[k] + u*0.5*(cei[k] - flo[k])));
        			
        			final double norm = k < kRise ? flo[k] :
        				k < kDrop ? cei[k] :
        					flo[k];
        			
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
        	}
        	else {
        		//candidates.add(new Candidate(bestStrength, bestA, bestKRise, bestKDrop, k0));
        		// cmap.put(Double.valueOf(uniqueStrength), new Candidate(uniqueStrength, bestA, bestKRise, bestKDrop, k0));
        		cmap.put(new CmapKey(bestStrength), new Candidate(bestStrength, bestA, bestKRise, bestKDrop, k0));
        	}
    		
        } // end loop over k
        

        for (;
        		//!candidates.isEmpty()
        		!cmap.isEmpty();) {
        	
        	final Candidate c = cmap.get(cmap.lastKey());
        			// candidates.iterator().next();

        	dots.put(Integer.valueOf(c.k0), new Dot(c.kRise, c.kDrop, c.strength));

        	final List<CmapKey> toBeRemoved = new ArrayList<CmapKey>();
        	
        	for (CmapKey k : cmap.keySet()) {
        		Candidate q = cmap.get(k);
        		if (q.kDrop >= c.kRise && q.kRise <= c.kDrop) {
        			toBeRemoved.add(k);
        		}
        	}
        	
        	// candidates.removeAll(toBeRemoved);
        	for (CmapKey k : toBeRemoved) {
        		cmap.remove(k);
        	}
        }
        
        // Merge dots and dashes
        
        if (!isDots) { dots.clear(); }
        if (!isDashes) { dashes.clear(); }
        
        new GerkeLib.Info("pre-merge nof. dots: %d", dots.size());
        new GerkeLib.Info("pre-merge nof. dash: %d", dashes.size());
        
        final KeyIterator dashIter = new KeyIterator(dashes.navigableKeySet().iterator());
        final KeyIterator dotsIter = new KeyIterator(dots.navigableKeySet().iterator());

        for (;;) {
        	if (!dotsIter.hasNext()) {
        		if (!dashIter.hasNext()) {
        			break;
        		}
        		else {
        			final Dash dash = dashes.get(dashIter.next());
        			tones.put(Integer.valueOf(dash.k), dash);
        		}
        	}
        	else if (!dashIter.hasNext()) {    // we have dots, but no dashes
        		final Dot dot = dots.get(dotsIter.next());
    			tones.put(Integer.valueOf(dot.k), dot);
        	}
        	else { // we have a dash and at least one dot, get the keys
        		final Integer dotKey = dotsIter.next();
        		final Integer dashKey = dashIter.next();
        		final Dot dot = dots.get(dotKey);
        		final Dash dash = dashes.get(dashKey);
        		
        		if (isBefore(dot, dash)) {
        			tones.put(Integer.valueOf(dot.k), dot);
        			dashIter.pushback(dashKey);
        		}
        		else if (isAfter(dot, dash)) {
        			tones.put(Integer.valueOf(dash.k), dash);
        			dotsIter.pushback(dotKey);
        		}
        		else if (!dotsIter.hasNext()) {  // clashing, but no more dots, drop this last dot
        			tones.put(Integer.valueOf(dash.k), dash);
        		}
        		else { // clashing first dot, what about second dot?
        			final Integer dot2Key = dotsIter.next();
            		final Dot dot2 = dots.get(dot2Key);
            		
            		if (isAfter(dot2, dash)) {
            			// drop the first dot, take the dash
            			tones.put(Integer.valueOf(dash.k), dash);
            			dotsIter.pushback(dot2Key);
            		}
            		else {
            			final double dotStrength = (dot.strength/dash.strength)*(dot2.strength/dash.strength);
            			// new GerkeLib.Info("dots strength: %f", dotStrength);
            			// strength comparison
            			if (dotStrength > twoDotsStrengthLimit) {
            				tones.put(Integer.valueOf(dot.k), dot);
            				tones.put(Integer.valueOf(dot2.k), dot2);
            			}
            			else {
            				tones.put(Integer.valueOf(dash.k), dash);
            			}
            		}
        		}
        	}
        }

        // we have tones
        // duplicated code from SlidingLinePlus
        
        reportDotsAndDashes(tones);
        overlapCheck(tones);
        new GerkeLib.Warning("overlaps: %d", overlapCount);
        
//        for (Integer key : tones.navigableKeySet()) {
//        	ToneBase tb = tones.get(key);
//        	if (tb instanceof Dot) {
//        		new GerkeLib.Info("o %12d %12d %12d", tb.rise, tb.k, tb.drop);
//        	}
//        	else {
//        		new GerkeLib.Info("= %12d %12d %12d", tb.rise, tb.k, tb.drop);
//        	}
//        }

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
    
//    private Candidate removeLast(SortedMap<Double, List<Candidate>> cmap) {
//    	final Double key = cmap.lastKey();
//    	final List<Candidate> list = cmap.get(key);
//    	final int listSize = list.size();
//    	if (listSize > 1) {
//    		final Candidate result = list.get(0);
//    		list.remove(result);
//    		if (!(list.size() == listSize-1)) { throw new RuntimeException(); }
//    		return result;
//    	}
//    	else {
//    		final Candidate result = list.get(0);
//    		cmap.remove(key);
//    		if (!(list.size() == 0)) { throw new RuntimeException(); }
//    		return result;
//    	}
//	}

//	private void cmapPut(double strength, Candidate candidate, SortedMap<Double, List<Candidate>> cmap) {
//    	final Double key = Double.valueOf(strength);
//		if (cmap.containsKey(key)) {
//			List<Candidate> list = cmap.get(key);
//			list.add(candidate);
//		}
//		else {
//			final List<Candidate> list = new ArrayList<Candidate>();
//			list.add(candidate);
//			cmap.put(key, list);
//		}
//	}

//	private double uniqueStrength(double strength, SortedMap<Double, Candidate> cmap) {
//
//    	for (double trialStrength = strength;
//    			;
//    			trialStrength = trialStrength == 0.0 ? 0.001 : 1.001*trialStrength) {
//    		
//    		if (!cmap.containsKey(Double.valueOf(trialStrength))) {
//    			return trialStrength;
//    		}
//    	}
//    	//return strength;
//	}

	private boolean isAfter(Dot dot, Dash dash) {
		return dot.rise > dash.drop;
	}

	private boolean isBefore(Dot dot, Dash dash) {
		return dot.drop < dash.rise;
	}

	private int overlapCount = 0;
    
    private void overlapCheck(NavigableMap<Integer, ToneBase> tones) {
	
    	ToneBase prev = null;
    	for (Integer key : tones.navigableKeySet()) {
    		final ToneBase tb = tones.get(key);
    		if (prev == null)  {
    			prev = tb;
    			continue;
    		}
    		else if (prev.drop < tb.rise) {
    			prev = tb;
    			continue;
    		}
    		else {
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

}
