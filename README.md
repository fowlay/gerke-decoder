# gerke-decoder

A decoder that translates Morse code audio to text.

## Platforms

- Linux
- Windows

## Dependencies

### Java

Running under Oracle JDK 1.8 version 202 and OpenJDK version 1.8.0_262
has been tested; other versions may work also.

### Optional: Gnuplot

The -P option, for plotting the signal amplitude, invokes the 'gnuplot' program.

### On Windows: Cygwin

When running on Windows it is recommended to set up Cygwin with X
support. The 'gnuplot' program, and Gnu 'make', can then be
conveniently installed as Cygwin packages.

## Building

To build the .jar file, type

    make

## Running

Invoke the program as follows:

    java -jar gerke-decoder.jar -h                for built-in help
    java -jar gerke-decoder.jar WAV_FILE          to decode a .wav file

## Test data

The following audio file is a partial recording of the early 2019-06-30 SAQ
transmission:

    http://privat.bahnhof.se/wb748077/alexanderson-day/grimeton-clip.wav

This file can be decoded with default settings.
