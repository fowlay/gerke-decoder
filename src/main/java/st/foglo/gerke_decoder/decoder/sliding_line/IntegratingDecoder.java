package st.foglo.gerke_decoder.decoder.sliding_line;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

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
    

    @Override
    public void execute() throws Exception {
        
        final double tsPerTu = 1.0/tsLength;
        new GerkeLib.Info("+++++ decoder 7, slices per TU: %f", tsPerTu);
        
        final double u = 1.0; // TODO, level, bind to a parameter
        
        final double aMax = 1.3;
        final double aMin = 0.8;
        
        // find dashes
        
        final int k0Lowest = (int)Math.round(2.0*aMax/tsLength) + 1;
        final int k0Highest = sigSize - (int)Math.round(2.0*aMax/tsLength) - 1;
       
        final SortedSet<Candidate> candidates = new TreeSet<Candidate>();
        
        for (int k0 = k0Lowest; k0 < k0Highest; k0++) {  // new GerkeLib.Info("k0: %d", k0);
        	
        	double bestStrength = Double.MIN_VALUE;
        	double bestA = Double.MIN_VALUE;
        	int bestKRise = Integer.MIN_VALUE;
        	int bestKDrop = Integer.MIN_VALUE;
        	
        	for (double a = aMin; a <= aMax; a += 0.1) {
        		double sum = 0.0;
        		double sumNorm = 0.0;
        		final int kRise = k0 - (int)Math.round(1.5*a/tsLength);
        		final int kDrop = k0 + (int)Math.round(1.5*a/tsLength);
        		for (int k = k0 - (int)Math.round(2.0*a/tsLength);
        				k < k0 + (int)Math.round(2.0*a/tsLength);
        				k++) {
        			final double g = k < kRise ? -1.0 : k < kDrop ? 1.0 : -1.0;
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
        	
        	// check if two dots is more likely, assuming best alfa
        	
        	double sum2dots = 0.0;
        	double sum2dotsNorm = 0.0;
        	final int kRise1st = k0 - (int)Math.round(1.5*bestA/tsLength);
        	final int kDrop1st = k0 - (int)Math.round(0.5*bestA/tsLength);
    		final int kRise2nd = k0 + (int)Math.round(0.5*bestA/tsLength);
    		final int kDrop2nd = k0 + (int)Math.round(1.5*bestA/tsLength);
    		for (int k = k0 - (int)Math.round(2.0*bestA/tsLength);
    				k < k0 + (int)Math.round(2.0*bestA/tsLength);
    				k++) {
    			final double g = k < kRise1st ? -1.0 :
    				k < kDrop1st ? 1.0 :
    					k < kRise2nd ? -1.0 :
    						k < kDrop2nd ? 1.0 : -1.0;
    			sum2dots += g*(sig[k] - (flo[k] + u*0.5*(cei[k] - flo[k])));
    			
    			final double norm2dots = k < kRise1st ? flo[k] :
    				k < kDrop1st ? cei[k] :
    					k < kRise2nd ? flo[k] :
    				k < kDrop2nd ? cei[k] :
    					flo[k];
    			
    			sum2dotsNorm += g*(norm2dots - (flo[k] + u*0.5*(cei[k] - flo[k])));
    		}
    		
    		final double strength2dots = sum2dots/sum2dotsNorm;
    		
    		final double twoDotsLimit = 0.2; // PARA .. smaller value favors 'i' over 't'
    		
//    		if (bestStrength > 0.0 && strength2dots > 0.0 && strength2dots/bestStrength > twoDotsLimit) {
//    			continue;
//    		}
//    		else {
//    			candidates.add(new Candidate(bestStrength, bestA, bestKRise, bestKDrop, k0));
//    		}
    		
    		if (bestStrength < 0.6) {
    			continue;
    		}
    		else {
			candidates.add(new Candidate(bestStrength, bestA, bestKRise, bestKDrop, k0));
		}
    		
    		
    		
    		
        } // end loop over k

        // loop over dash candidates, strongest first
        
        for (; !candidates.isEmpty();) {
        	
        	final Candidate c = candidates.iterator().next();
        	
        	// simple heuristic, drop very weak tones, PARA
        	if (c.strength < 0.1) {
        		break;
        	}
        	// new GerkeLib.Info("strength: %e", c.strength);
        	tones.put(Integer.valueOf(c.k0), new Dash(c.kRise, c.kDrop));
        	
        	for (int k = c.kRise; k <= c.kDrop; k++) {
        		sig[k] = flo[k];
        	}
        	
        	final List<Candidate> toCut = new ArrayList<Candidate>();
        	
        	for (Candidate q : candidates) {
        		if (q.kDrop >= c.kRise && q.kRise <= c.kDrop) {
        			toCut.add(q);
        		}
        	}
        	
        	candidates.removeAll(toCut);
        }
        
        // now find the dots
        
        candidates.clear();
        
        final int k0LowestDots = (int)Math.round(1.0*aMax/tsLength) + 1;
        final int k0HighestDots = sigSize - (int)Math.round(1.0*aMax/tsLength) - 1;
        
        for (int k0 = k0LowestDots; k0 < k0HighestDots; k0++) {  // new GerkeLib.Info("k0: %d", k0);
        	
        	double bestStrength = Double.MIN_VALUE;
        	double bestA = Double.MIN_VALUE;
        	int bestKRise = Integer.MIN_VALUE;
        	int bestKDrop = Integer.MIN_VALUE;
        	
        	for (double a = aMin; a <= aMax; a += 0.1) {
        		double sum = 0.0;
        		double sumNorm = 0.0;
        		final int kRise = k0 - (int)Math.round(0.5*a/tsLength);
        		final int kDrop = k0 + (int)Math.round(0.5*a/tsLength);
        		for (int k = k0 - (int)Math.round(1.0*a/tsLength);
        				k < k0 + (int)Math.round(1.0*a/tsLength);
        				k++) {
        			final double g = k < kRise ? -1.0 : k < kDrop  ? 1.0 : -1.0;
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
        	
    		candidates.add(new Candidate(bestStrength, bestA, bestKRise, bestKDrop, k0));
    		
        } // end loop over k
        

        for (; !candidates.isEmpty();) {
        	
        	final Candidate c = candidates.iterator().next();
        	
        	// simple heuristic, drop very weak tones, PARA
        	if (c.strength < 0.5) {
        		break;
        	}
        	// new GerkeLib.Info("strength: %e", c.strength);
        	tones.put(Integer.valueOf(c.k0), new Dot(c.kRise, c.kDrop));

        	final List<Candidate> toCut = new ArrayList<Candidate>();
        	
        	for (Candidate q : candidates) {
        		if (q.kDrop >= c.kRise && q.kRise <= c.kDrop) {
        			toCut.add(q);
        		}
        	}
        	
        	candidates.removeAll(toCut);
        }
        

      
        // we have tones
        // System.out.println(String.format("nof. tones: %d", tones.size()));
        // duplicated code from SlidingLinePlus
        
        reportDotsAndDashes(tones);
        
        overlapCheck(tones);
        if (overlapCount > 0) {
        	new GerkeLib.Warning("overlaps: %d", overlapCount);
        }
        
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
