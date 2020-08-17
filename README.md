# gerke-decoder

A decoder that translates Morse code audio to text.

## Dependencies

Cygwin
Oracle Java JDK 1.8 version 202
Gnu 'make'
gnuplot

Other shell environments than Cygwin may be usable also. Other Java
versions may be usable also. The 'gnuplot' program is only needed with
the -P option.

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
