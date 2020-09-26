Version 1.8-coherent-gaussian

EXPERIMENTAL: Integrating the signal over multiple time slices,
coherently and with a Gaussian weight function. This scheme may
improve the S/N ratio, but only if the signal has good phase
stability. Signals from the Grimeton transmitter seem to have
considerable short-term frequency drift, making this signal detection
scheme less successful.

NOTE: The -Q option (phase plot) is not supported on this branch.

Version 1.8:

DONE: Spikes and dips removal based on the integral of squared
deviation from threshold, instead of just duration.

DONE: Include a curve for the transitions that remain after dips and
spikes removal. This curve is drawn at the bottom edge of the plot and
visualizes the input to the decoding procedure.


Version 1.7:

DONE: Ensure text buffer is flushed also if the analysis stops in the
middle of a dash or a dot.

DONE: Gaussian blur for reduced spikiness.
