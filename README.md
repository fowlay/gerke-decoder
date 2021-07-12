# gerke-decoder

A decoder that translates Morse code audio to text.

## Version history

|Version|What|
|-------|----|
|1.3|Cygwin/Windows version|
|1.4|Linux support|
|1.5|Optional phase angle plot|
|1.6|Spikes suppression, selectable plot interval|
|1.7|Gaussian blur, frequency plot|
|1.8|Dropouts and spikes removal, improved plot|
|2.0|Selectable decoders and filtering|
|2.0.1|Corrections|
|2.0.2|Corrected default dips-merge limit parameter|

## Platforms

- Linux
- Windows with Cygwin/X

## Dependencies

### Java

Running under Oracle JDK 1.8 version 202 and OpenJDK version
1.8.0_262. Other versions may work also.

### Optional: Gnuplot

The -S, -A and -P options, for plotting signal frequency, amplitude
and phase, invoke the 'gnuplot' program. On Windows with Cygwin/X the
gnuplot-base and gnuplot-X11 packages are needed.

### GNU Make

Building requires GNU Make. On Windows with Cygwin/X GNU Make is
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

## Running

Invoke the program as follows:

    bin/gerke-decoder -h                for built-in help
    bin/gerke-decoder WAV_FILE          to decode a .wav file

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

### Verbosity

Add the -v option to have some diagnostics printed. To get even more
diagnostics use -v -v or -v -v -v.

Diagnostics are printed to the standard error stream.

### Frequency

By default a search for tone signals in the range 400..1200 Hz is
made. The decoder will choose the best frequency in that range.  A
non-default search range may be set with the -F option, e.g.:

    -F 3000,6000

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

When the -v option is given the effective WPM, as calculated from the
timing of dots and dashes in decoded characters, will be
reported. Re-running with the -w option set to this value may result
in somewhat improved decoding.

### Expanded spaces

If spaces between characters and words are expanded, this can be
compensated for by specifying e.g:

    -W 1.2

### Clipping level

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

### Sample period

For accurate decoding the signal amplitude should be sampled with a
period of about 0.1 TU. A non-default sampling period of 0.15 TU can
be given like this:

    -u 0.15

### Gaussian sigma

To further reduce signal fluctuations a Gaussian average of samples is
taken.  A sigma value of about 0.25 TU is used by default. To specify
a non-default sigma value, add

    -s 0.35

### Decoding method

Three different decoding methods are provided:

1: Tone/silence crossings based: Dots and dashes are recognized as the
signal amplitude raises above and falls below a threshold level. The
level is adjustable with the -u option.

2: Pattern matching: The signal is multiplied with a rectangular wave
function representing a character. The product is integrated over the
extent of the character and the best matching character is chosen. The
beginning and end of a character is still based on tone/silence
crossings.

3: Dips based: The extent of a character is determined from level
crossings, as in method 1.  Within the character a search is made for
dips, by matching against a negative gaussian function with a width of
about 1 TU. Dashes and dots are then identified from the positions of
the dips.

The dips based method is enabled by default. Another method can be
requested with the -D option.

### Threshold level

The threshold between tone and silence is determined
automatically. The threshold level will be proportional to the signal
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
specify a 10 s interval starting at 55 s into the recording, use

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

The signal vs. frequency graph can be made more peaked by increasing
the -q option value (try -h to see what the default is). Note however
that this may cause decoding to be impaired, so do this only for
finding the frequency.

### Offset and length

The -o option specifies an offset, in seconds, into the .wav file. The
-l option specifies a length in seconds. When processing a large
recording these options can be used to limit the amount of data to be
held in RAM. For example, assuming a segment starting at 4200 s and
extending for 180 s is to be decoded, specify

    -o 4200 -l 180

All options refer to the same time axis, starting at 0 seconds at the
beginning of the .wav file.  To plot a 10 s interval out of the 180 s
segment, add

    -A -Z 4300,10

### Timestamps

The -t option causes a timestamp in seconds to be inserted after every
decoded word.

### Experimental parameters

A number of experimental parameters can be set with the -H option. To
see the default values, use -h. Values must be given as a
comma-separated space-free string, with no values omitted. The
parameters are:

    DIP     Threshold for dips removal
    SPIKE   Threshold for spikes removal
    BREAK   Break very long dashes in two: 0=disabled, 1=enabled
    FILTER  Low-pass filter
    CUTOFF  Low-pass filter cutoff frequency relative to 1/TU
    ORDER   Filter order
    PL      Phase locking
    PLWIDTH Phase locking width
    DIPM    Dips merge limit

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
