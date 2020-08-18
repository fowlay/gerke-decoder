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

classes/st/foglo/gerke_decoder/GerkeLib.class: src/GerkeLib.java
	mkdir -p classes
	javac -d classes src/GerkeLib.java

classes/st/foglo/gerke_decoder/GerkeDecoder.class: src/GerkeDecoder.java \
    classes/st/foglo/gerke_decoder/GerkeLib.class
	mkdir -p classes
	javac -d classes -classpath classes src/GerkeDecoder.java

.PHONY: clean
clean:
	rm -rf gerke-decode.jar classes
