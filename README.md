# gerke-decoder

A decoder that translates Morse code audio to text.

Copyright (C) 2020-2024 Rabbe Fogelholm

## Version history

<table>
<tr><th>Version</th><th style="text-align:left">What</th></tr>
<tr><td>1.3</td><td>Cygwin/Windows version</td></tr>
<tr><td>1.4</td><td>Linux support</td></tr>
<tr><td>1.5</td><td>Optional phase angle plot</td></tr>
<tr><td>1.6</td><td>Spikes suppression, selectable plot interval</td></tr>
<tr><td>1.7</td><td>Gaussian blur, frequency plot</td></tr>
<tr><td>1.8</td><td>Dropouts and spikes removal, improved plot</td></tr>
<tr><td>2.0</td><td>Selectable decoders and filtering</td></tr>
<tr><td>2.0.1</td><td>Corrections</td></tr>
<tr><td>2.0.2</td><td>Corrected default dips-merge limit parameter</td></tr>
<tr><td>2.0.2.1</td><td>Least-squares decoding, first version</td></tr>
<tr><td>2.0.2.2</td><td>Include decoded signal in plot</td></tr>
<tr><td>2.0.2.3</td><td>Support option -t with decoder -D4</td></tr>
<tr><td>2.0.2.4</td><td>Candidate for 2.1.0 on master branch</td></tr>
<tr><td>2.1.0</td><td>Least-squares decoding</td></tr>
<tr><td>2.1.1</td><td>Various corrections</td></tr>
<tr><td>2.1.1.1</td><td>Refactoring</td></tr>
<tr><td>3.0.0</td><td>Adapting to frequency drift and fading</td></tr>
<tr><td>3.0.1</td><td>Guard against all-zeroes segments in .wav file</td></tr>
<tr><td>3.0.2</td><td>Clipping depth as hidden parameter, default 0.05</td></tr>
<tr><td>3.0.3</td><td>Fix coding fault, improve performance</td></tr>
<tr><td>3.1.0</td><td>Add tone/space lengths histogram</td></tr>
<tr><td>3.1.1</td><td>Support .mp3 input</td></tr>
<tr><td>3.1.2</td><td>Correct Maven archive URL</td></tr>
<tr><td>3.1.3</td><td>Break up oversized dashes</td></tr>
<tr><td>3.1.4</td><td>Release content tidyup</td></tr>
<tr><td>3.1.5</td><td>Fix gnuplot behaviour for Cygwin/X</td></tr>
<tr><td>3.1.6</td><td>Add some missing code table entries</td></tr>
<tr><td>3.1.7</td><td>Adjust build scripts</td></tr>
<tr><td>3.1.8</td><td>Reporting number of dots and dashes (certain decoders only)</td></tr>
<tr><td>3.2.0</td><td>Introduce integrating decoder</td></tr>
<tr><td>3.2.1</td><td>Internal consistency fix, tweak parameters</td></tr>
<tr><td>3.2.2</td><td>Fix Makefile issue</td></tr>
<tr><td>3.2.3</td><td>Take tone separation from tone midpoints</td></tr>
<tr><td>3.2.4</td><td>Handle 24-bit 2-channel WAV, decode ..--. to '!'</td></tr>
<tr><td>3.2.5</td><td>Handle 24-bit 1-channel WAV</td></tr>
<tr><td>3.2.6</td><td>Build with Java 8 compatibility</td></tr>
<tr><td>3.2.7</td><td>Improve timings summary</td></tr>
<tr><td>3.2.8</td><td>Robustness fix for 'make test'</td></tr>
<tr><td>3.2.9</td><td>More integrating decoder hidden parameters</td></tr>
<tr><td>3.2.10</td><td>Fix for crash at certain wave file lengths</td></tr>
</table>

## Platforms

- Linux
- Windows with Cygwin/X

## Dependencies

### Java

OpenJDK version 11 or higher is recommended. Binaries are built with
Java 8 compatibility.

### Optional: mpg123

With this conversion utility installed, audio files in .mp3 format can
be decoded.

### Optional: Gnuplot

The -S, -A, -P, -Y and -M options, for plotting various aspects of the
recorded signal, invoke the 'gnuplot' program. On major Linux
distributions, if 'gnuplot' is not already installed, use the provided
package manager to install it.

On Windows with Cygwin/X the
gnuplot-base and gnuplot-X11 packages are needed.

### GNU Make

Building requires GNU Make. On Windows with Cygwin/X, GNU Make is
provided in the 'make' package.

### The 'iirj' filter package

The build process downloads the 'iirj' filter package. For licensing
information, read here:
<https://github.com/berndporr/iirj/blob/master/LICENSE.txt>

## Disk requirements

Installing gerke-decoder requires of the order 50 MB of disk space.

## Building

To build executables, type

    make

The build procedure will install a dedicated copy of Maven in the
local directory. This installation will not interfere with possible
other use of Maven on the same host. For licensing info, read here:
<http://maven.apache.org/#>

NOTE: It is recommended that the working directory is on a path that
doesn't have embedded spaces. Likewise the Java installation should be
on a space-free path. This may be a problem when using Oracle JDK on
Windows, which typically is installed under C:\Program Files\ - when
using Cygwin this can be overcome by setting up a symlink to the
C:\Program Files\Java\ directory.

Building an executable jar (optional) can be done with

    make gerke-decoder.jar

Running a quick test can be done with

    make test

Removing downloaded and built objects can be done with

    make clean

## Upgrading

If the source tree was downloaded using a "git clone" command,
upgrading to a more recent version can be done with

    git pull --rebase
    make clean && make

## Building version 2.1.1

Version 2.1.1 is not the latest; it is maintained on a branch. To
build it, do

    git checkout 2.1.1-m1
    make

## Running

Invoke the program as follows:

    bin/gerke-decoder -h                   for built-in help
    bin/gerke-decoder WAV_FILE             to decode a .wav file
    bin/gerke-decoder MP3_FILE             to decode a .mp3 file

The executable jar is similarly invoked:

    java -jar gerke-decoder.jar -h         for built-in help
    java -jar gerke-decoder.jar WAV_FILE   to decode a .wav file

Note: The executable jar requires a .wav input file.

## Assumptions

The relation between WPM (words per minute) and TU (length of a dot in
ms) is taken to be

    TU = 1200/WPM

as described here: <https://en.wikipedia.org/wiki/Morse_code#Timing>
(the "PARIS" formula).

The decoder expects 3 TU of inter-character silence and 7 TU of
inter-word silence. Deviations may cause garbled characters or false
word breaks.

## Options

### Decoding method

Seven different decoding methods are provided, selectable with the -D option:

1: Tone/silence: Dots and dashes are recognized as the
signal amplitude raises above and falls below a threshold level. The
level is adjustable with the -u option.

2: Patterns: The signal is multiplied with a rectangular wave
function representing a character. The product is integrated over the
extent of the character and the best matching character is chosen. The
beginning and end of a character is still based on tone/silence
crossings.

3: Dips: The extent of a character is determined from level
crossings, as in method 1.  Within the character a search is made for
dips, by matching against a negative gaussian function with a width of
about 1 TU. Dashes and dots are then identified from the positions of
the dips.

4: Prototype segments: Dashes are recognized by
least-squares fitting a straight line to dash-length time
intervals. Dots are then recognized by trying dot-length time
intervals that don't clash with the dashes. This decoding method
is of a prototype nature.

5: Sliding segments: Line segments are fitted to the signal data,
thereby locating dashes and dots.

6: Adaptive segments: Similar to the no. 5 method, plus it adapts
to frequency drift and fading.

7: Integrating: Sums over short segments of the signal are examined,
locating probable dots and dashes. This method does not require
detection of tone/silence crossings, which tends to be very sensitive
to noise.
<span style="font-weight: bold;">This is the default decoding
method</span>.

### Verbosity

Add the -v option to have some diagnostics printed. To get even more
diagnostics use -v -v or -v -v -v.

Diagnostics are printed to the standard error stream.

### Frequency

By default a search for tone signals in the range 400..1200 Hz is
made. The decoder will choose the best frequency in that range.  A
non-default search range may be set with the -F option, e.g.:

    -F 16900,17500

Add the -S option to get a plot of average signal amplitude versus
frequency. There should be a maximum somewhere in the frequency range.
If no maximum is seen, try a different search range.

Once the tone frequency has been determined it can be passed to the
decoder with the -f option, e.g.:

    -f 690

If the frequency is given with the -f option, the somewhat
time-consuming frequency search is skipped.

### WPM

The decoder assumes by default that the WPM speed is 15, which implies
a dot length of 1200/15 = 80 ms. If the WPM speed of the .wav file is
higher or lower than 15, the -w option must be used. For example, if
the speed is believed to be 22 WPM, then use

    -w 22

A too high value of the -w option will cause the decoder to interpret
many tones as dashes. A too low value will cause tones to be
interpreted as dots. When a reasonable setting has been found the
decoder will distinguish dashes and dots properly.

The effective WPM, as calculated from the timing of dots and dashes in
decoded characters, will be reported when the -v option is used.
Re-running with the -w option set to this value may result in somewhat
improved decoding.

### Compensating for expanded spaces

(This option is only effective with the "dips" decoder.)

If silent spaces between characters and words are exaggerated, this can
be compensated for by specifying e.g:

    -W 1.2

### Clipping level

(This option is ignored by the "adaptive line segments" decoder)

By default the decoder will apply a small degree of clipping, with
intention to reduce the impact of spiky noise. With the -v option the
default level of clipping is reported. More agressive clipping can be
specified with the -c option, by giving a smaller value than the
reported default. For example, if the default level of clipping is
reported as 6655, then try e.g.:

    -c 3000

The clipping level is applied to the recording as a whole. In the
presence of fading it may thus be that clipping is not effective in
faded parts of the recording. To process different parts of the
recording differently the -o and -l options can be used.

### Time slice period

The signal is estimated at points that are of the order 0.1 TU apart.
This time slice period is decoder dependent. To decrease the time
slice period to 80% of the default value, use

    -q 0.8

### Gaussian sigma

To further reduce signal fluctuations a Gaussian average of samples is
taken.  A sigma value of about 0.18 TU is used by default. To specify
a non-default sigma value, use

    -s 0.35

### Threshold level

The threshold between tone and silence is determined
automatically. The threshold level will follow the signal
level, so modest fading will not be an issue.

In noisy conditions some improvement may be obtained by setting the
threshold slightly lower or higher. The -u option can be used for
this; the default value is 1.0, so to set the threshold slightly
higher try e.g.:

    -u 1.05

When experimenting with clipping it may be necessary to also adjust
the threshold.

### Signal plot

The signal as a function of time can be studied grapically. Request
plotting with the -A option:

    -A

To get a good view it may be necessary to select a sufficiently short
time interval. For WPM around 15 a 10 s interval may be convenient. To
specify an interval starting at 55 s into the recording and lasting for
10 s, use

    -Z 55,10

When studying a decoding case that includes some garbled words it may
be interesting to plot the signal at that point. Add the -t option to
get timestamps inserted in the decoded text. This gives guidance on
how to set the -Z option.

### Phase plot

The relative phase angle of the signal may be plotted with the -P
option. If the decoder frequency is set correctly, the phase angle
should wander only slowly.

### Frequency plot

Signal content versus frequency can be plotted with the -S option.
This option cannot be used in combination with the -f option, and
not in combination with the "adaptive segments" decoder.

The signal vs. frequency graph can be made more peaked by increasing
the -q option value (try -h to see what the default is). Note however
that this may cause decoding to be impaired, so do this only for
finding the frequency.

### Frequency stability plot

This feature is enabled with option -Y, and is available with decoding
methods 4, 5 and 6. The signal frequency is plotted as a function of
time. A time interval can be specified with the -Z option.

### Tone and space lengths histograms

This feature is enabled with option -M:

    -M 1            tone lengths
    -M 0            space lengths
    -M 1,0          tone lengths and space lengths

### Offset and length

The -o option specifies an offset, in seconds, into the .wav file. The
-l option specifies a length in seconds. When processing a large
recording these options can be used to limit the amount of data to be
held in RAM. For example, assuming that a segment starting at 4200 s and
extending for 180 s is to be decoded, then specify

    -o 4200 -l 180

All options, except for -l, refer to the same time axis, starting at 0
seconds at the beginning of the .wav file.  To plot a 10 s interval
within the 180 s segment, add e.g.

    -o 4200 -l 180 -A -Z 4300,10

### Timestamps

The -t option causes a timestamp in seconds to be inserted after every
decoded word.

### Output text style

The -T option controls casification and line length:

    -T U            upper case
    -T L            lower case (the default)
    -T C            capitalized

The default line length is 72; a non-default value may be specified:

    -T U,40

### Experimental parameters

A number of experimental parameters can be set with the -H option. To
see the default values, use -h. Values must be given as a
comma-separated space-free string, with no values omitted. The
parameters are:

    DIP              Threshold for dips removal
    SPIKE            Threshold for spikes removal
    BREAK_LONG_DASH  Break very long dashes in two: 0=disabled, 1=enabled
    FILTER           Low-pass filter
    CUTOFF           Low-pass filter cutoff frequency relative to 1/TU
    ORDER            Filter order
    PHASELOCKED      Phase locking
    PLWIDTH          Phase locking width
    DIP_MERGE_LIM    Dips merge limit
    DIP_STRENGTH_MIN Dips strength minimum
    CLIP_DEPTH       Clipping depth (only with "adaptive segments" decoder)
    HALF_WIDTH       Sliding line half-width
    SPIKE_WIDTH_MAX  Maximum width of a spike (unit is TU)
    CRACK_WIDTH_MAX  Maximum width of a crack (unit is TU)

Valid values for FILTER are:

    b       Butterworth
    cI      Chebyshev type I
    w       Sliding window (ignoring ORDER)
    n       No filter (ignoring CUTOFF and ORDER)

### Version

The -V option causes the program version to be displayed.

### Help

The -h options causes a brief summary of the options to be displayed.

## Test data

The following audio file is a partial recording of the early
2019-06-30 SAQ transmission:

    http://privat.bahnhof.se/wb748077/alexanderson-day/grimeton-clip.wav

This file can be decoded with default settings.

## VLF radio reception

The spectrum for VLF radio transmissions overlaps at the lower end
with the frequency range of computer soundcards. With an antenna
connected to the soundcard input port it is possible to record the
signal directly to a .wav file, using a sound recorder program. This
program, with a suitably chosen -F option, can then be used for
decoding the transmission.

## The name

The decoder is named in memory of an early contributor to
telecommunications:
<https://en.wikipedia.org/wiki/Friedrich_Clemens_Gerke>
