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

## Platforms

- Linux
- Windows

## Dependencies

### Java

Running under Oracle JDK 1.8 version 202 and OpenJDK version 1.8.0_262
has been tested; other versions may work also.

### Optional: Gnuplot

The -P and -Q options, for plotting signal amplitude and phase, invoke
the 'gnuplot' program.

### On Windows: Cygwin

When running on Windows it is recommended to set up Cygwin with X
support. The 'gnuplot' program, and Gnu 'make', can then be
conveniently installed as Cygwin packages.

Running on Windows without Cygwin works also, except for the plot
features.

## Building

To build the .jar file, type

    make

## Running

Invoke the program as follows:

    java -jar gerke-decoder.jar -h                for built-in help
    java -jar gerke-decoder.jar WAV_FILE          to decode a .wav file

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

Add the -v option to get some diagnostics printed. To get even more
diagnostics use -v -v.

Diagnostics are printed to the standard error stream.

### Frequency

By default a search for tone signals in the range 400..1200 Hz is
made. The decoder will choose the best frequency in that range.  A
non-default search range may be set with the -F option, e.g.:

    -F 3000,6000

Add the -S option to get a plot of average signal amplitude versus
frequency. There should be a maximum somewhere in the frequency range.
If no maximum is seen, try a wider search range.

The signal vs. frequency graph can be made more sharp by setting a
small value for the -w option. The default is 15, so a value like 10
or 5 may be tried. Note however that the -w option affects decoding,
so use this only while inspecting the frequency content.

The chosen best frequency is reported when the -v option is
given. Once the frequency has been determined it can be passed to the
decoder with the -f option, e.g.:

    -f 690

If the frequency is prescribed with the -f option then the somewhat
time-consuming frequency search is skipped.

### WPM

The decoder assumes that the WPM speed is 15, which implies a dot
length of 1200/15 = 80 ms. If the WPM speed of the .wav file is higher
or lower than 15, the -w option must be used. For example, if the
speed is believed to be 22 WPM, then use

    -w 22

A too high value of the -w option will cause the decoder to interpret
many tones as dashes. A too low value will cause tones to be
interpreted as dots. When a reasonable setting has been found the
decoder will distinguish dashes and dots properly.

When the -v option is given the effective WPM, as calculated from the
timing of dots and dashes in decoded characters, will be
reported. Re-running with the -w option set to this value may result
in some improvement.

### Clipping

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

The signal, after gaussian blurring, as a function of time, the
silence/tone threshold and the resolved binary output after dips and
spikes removal can be studied grapically. Request plotting with the -P
option:

    -P

To get a good view it may be necessary to select a sufficiently short
time interval. For WPM around 15 a 10 s interval may be convenient. To
specify a 10 s interval starting at 55 s into the recording, use

    -Z 55,10

When studying a decoding case that includes some garbled words it may
be interesting to plot the signal at that point. Add the -t option to
get timestamps inserted in the decoded text. This gives guidance on
how to set the -Z option.

### Phase plot

NOTE: This plot is not supported in version 1.8-coherent-gaussian

The relative phase angle of the signal may be plotted with the -Q
option. If the decoder frequency is set correctly, the phase angle
should wander only slowly.

### Offset and length

The -o option specifies an offset, in seconds, into the .wav file. The
-l option specifies a length in seconds. When processing a large
recording these options can be used to limit the amount of data to be
held in RAM. For example, assuming a segment starting at 4200 s and
extending for 180 s is to be decoded, specify

    -o 4200 -l 180

All options refer to the same time axis, starting at 0 seconds at the
beginning of the .wav file.  To plot a 10 s interval out of the 180 s
segment one can therefore add

    -P -Z 4300,10

### Dip and spike removal parameters

The default setting is

    -D 0.005,0.005

In noisy conditions there may be sub-TU dips and spikes. The decoder
removes those before resolving the signal into characters. Tuning the
values may give some improvement; use the signal plot to monitor the
effect.

### Low-level detection parameters

The -X option specifies two detection parameters. The default setting
is

    -X 0.09,0.19

where 0.09 TU is the length of signal collecting time slices and 0.19
TU defines the width of a "gaussian blur" averaging that reduces false
tone/silence transitions. Tuning these parameters may possibly give
somewhat improved decoding; use the signal plot for visualizing the
effect.

### Timestamps

The -t option causes a timestamp in seconds to be inserted after every
decoded word.

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
connected to the soundcard input port it ought to be possible to
record the signal directly to a .wav file, using a sound recorder
program. This program, with a suitably chosen -F option, can then
presumably be used for decoding the transmission.

## The name

The decoder is named in memory of an early contributor to
telecommunications:
<https://en.wikipedia.org/wiki/Friedrich_Clemens_Gerke>
