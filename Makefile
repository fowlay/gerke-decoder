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


# For alternative mirrors, see https://www.apache.org/mirrors/
APACHE_MIRROR = https://ftp.acc.umu.se/mirror/apache.org/
APACHE_REL = 3.6.3


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
	declare expected=154a2f5ac9fa71c715a90cd51aeea4ad && \
	declare md5="$$(java -jar gerke-decoder.jar -v \
                 grimeton-clip.wav 2>&1 1>/dev/null | \
                 sed -e '/MD5/!d' -e 's|.* ||' -e 's|\r||')" && \
	if [ $$md5 = $$expected ]; then echo test successful; \
	else echo test failed, expected: $$expected, actual: $$md5; \
             java -jar gerke-decoder.jar -v grimeton-clip.wav; fi

grimeton-clip.wav:
	rm -f $@
	wget http://privat.bahnhof.se/wb748077/alexanderson-day/$@


.PHONY: x

x:
	env "PATH=apache-maven-$(APACHE_REL)/bin:$$PATH" mvn compile




# x: apache-maven-$(APACHE_REL)/conf/settings.xml

apache-maven-$(APACHE_REL)/conf/settings.xml:
	wget $(APACHE_MIRROR)/maven/maven-3/$(APACHE_REL)/binaries/apache-maven-$(APACHE_REL)-bin.tar.gz \
	  --output-document=- \
	| tar -xzf -
	sed -i -e "/<!-- localRepository/s|.*|<localRepository>m2</localRepository> <!--|" $@
