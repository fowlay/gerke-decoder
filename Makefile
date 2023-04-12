## gerke-decoder - translates Morse code audio to text
##
## Copyright (C) 2020-2023 Rabbe Fogelholm
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


## The directory structure was created by running
##
##     mvn archetype:generate \
##         -DgroupId=st.foglo.gerke_decoder \
##         -DartifactId=st.foglo.gerke_decoder \
##         -DarchetypeArtifactId=maven-archetype-quickstart \
##         -DinteractiveMode=false
##
## and moving all items under the top directory up one level,
## and the src/test directory tree.

GERKE_DECODER_REL = $(shell sed -e 's|^  <version>\(.*\)</version>|\1|p' -e d pom.xml)
APACHE_REL = 3.6.3

## For alternative mirrors, see https://www.apache.org/mirrors/
## APACHE_MIRROR = https://ftp.acc.umu.se/mirror/apache.org/ .. defunct since autumn 2021
APACHE_MIRROR = https://dlcdn.apache.org/


.PHONY: test clean realclean
.SILENT: test


## Make gerke-decoder and dependencies

SRC = pom.xml $(shell find src/main/java/st/foglo/gerke_decoder -name '*.java')

bin/gerke-decoder: lib/gerke-decoder.template target/gerke_decoder-$(GERKE_DECODER_REL).jar
	mkdir --parents $$(dirname $@)
	sed -e 's|@GERKE_DECODER_REL@|$(GERKE_DECODER_REL)|' $< >$@
	chmod a+x $@

target/gerke_decoder-$(GERKE_DECODER_REL).jar: apache-maven-$(APACHE_REL)/conf/settings.xml $(SRC)
	env "PATH=apache-maven-$(APACHE_REL)/bin:$$PATH" mvn package


## Make executable jar (this make-target assumes a Linux environment; TODO: fix for Cygwin/X)

gerke-decoder.jar: target/gerke_decoder-$(GERKE_DECODER_REL).jar \
	    m2/uk/me/berndporr/iirj/1.1/iirj-1.1.jar \
	    m2/org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar
	rm -rf standalone-classes
	mkdir standalone-classes
	cd standalone-classes && \
	cp ../target/gerke_decoder-$(GERKE_DECODER_REL).jar ./ && \
        jar xf gerke_decoder-$(GERKE_DECODER_REL).jar && \
	cp ../m2/uk/me/berndporr/iirj/1.1/iirj-1.1.jar ./ && \
        jar xf iirj-1.1.jar && \
	cp ../m2/org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar ./ && \
        jar xf commons-math3-3.6.1.jar && \
	rm gerke_decoder-$(GERKE_DECODER_REL).jar iirj-1.1.jar commons-math3-3.6.1.jar && \
	rm -rf META-INF
	jar cfe $@ st.foglo.gerke_decoder.GerkeDecoder -C standalone-classes .
	rm -rf standalone-classes


## Download Maven to a local directory, define a local path for repository

apache-maven-$(APACHE_REL)/conf/settings.xml:
	wget $(APACHE_MIRROR)/maven/maven-3/$(APACHE_REL)/binaries/apache-maven-$(APACHE_REL)-bin.tar.gz \
	  --output-document=- \
	| tar -xzf -
	sed -i -e "/<!-- localRepository/s|.*|<localRepository>m2</localRepository> <!--|" $@


## Quick test

test: gerke-decoder.jar bin/gerke-decoder grimeton-clip.wav
	cmd="bin/gerke-decoder -v -o1 -l87 -w16 grimeton-clip.wav" && \
	$$cmd && \
	expected=fffe9b72b43f4fe65953565afd4d87c3 && \
	md5="$$($$cmd 2>&1 | sed -e '/MD5/!d' -e 's|.* ||' -e 's|\r||')" && \
	if [ $$md5 = $$expected ]; then echo first test successful; \
	else echo test failed, expected: $$expected, actual: $$md5; fi && \
	cmd="java -jar gerke-decoder.jar -v -o1 -l87 -w16 grimeton-clip.wav" && \
	md5="$$($$cmd 2>&1 | sed -e '/MD5/!d' -e 's|.* ||' -e 's|\r||')" && \
	if [ $$md5 = $$expected ]; then echo second test successful; \
	else echo test failed, expected: $$expected, actual: $$md5; fi

grimeton-clip.wav:
	wget http://privat.bahnhof.se/wb748077/alexanderson-day/$@


## Removal

clean:
	rm -rf bin target gerke-decoder.jar

realclean: clean
	rm -rf m2 apache-maven-$(APACHE_REL) grimeton-clip.wav
