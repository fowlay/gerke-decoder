## gerke-decoder - translates Morse code audio to text
##
## Copyright (C) 2020 Rabbe Fogelholm
##
## This program is free software: you can redistribute it and/or modify
## it under the terms of the GNU General Public License as published by
## the Free Software Foundation, either version 3 of the License, or
## (at your option) any later version.
##
## This program is distributed in the hope that it will be useful,
## but WITHOUT ANY WARRANTY; without even the implied warranty of
## MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
## GNU General Public License for more details.
##
## You should have received a copy of the GNU General Public License
## along with this program.  If not, see <http://www.gnu.org/licenses/>.


gerke-decoder.jar: \
    classes/st/foglo/gerke_decoder/GerkeLib.class \
    classes/st/foglo/gerke_decoder/GerkeDecoder.class
	jar cfe gerke-decoder.jar st.foglo.gerke_decoder.GerkeDecoder -C classes .

classes/st/foglo/gerke_decoder/GerkeLib.class: src/st/foglo/gerke_decoder/GerkeLib.java
	mkdir -p classes
	javac -d classes src/st/foglo/gerke_decoder/GerkeLib.java

classes/st/foglo/gerke_decoder/GerkeDecoder.class: src/st/foglo/gerke_decoder/GerkeDecoder.java \
    classes/st/foglo/gerke_decoder/GerkeLib.class
	mkdir -p classes
	javac -d classes -classpath classes src/st/foglo/gerke_decoder/GerkeDecoder.java

.PHONY: clean test

clean:
	rm -rf gerke-decode.jar classes

.SILENT: test

test: gerke-decoder.jar grimeton-clip.wav
	declare expected=90d879cd4423ea56997d976cbb42646e && \
	declare md5="$$(java -jar gerke-decoder.jar -v \
                 grimeton-clip.wav 2>&1 1>/dev/null | \
                 sed -e '/MD5/!d' -e 's|.* ||' -e 's|\r||')" && \
	if [ $$md5 = $$expected ]; then echo test successful; \
	else echo test failed, expected: $$expected, actual: $$md5; fi

grimeton-clip.wav:
	rm -f $@
	wget http://privat.bahnhof.se/wb748077/alexanderson-day/$@
