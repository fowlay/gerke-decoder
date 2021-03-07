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

GERKE_DECODER_REL = 2.0
APACHE_REL = 3.6.3

## For alternative mirrors, see https://www.apache.org/mirrors/
APACHE_MIRROR = https://ftp.acc.umu.se/mirror/apache.org/



## Make gerke-decoder and dependencies

SRC = $(wildcard src/main/java/st/foglo/gerke_decoder/*.java)

target/gerke_decoder-$(GERKE_DECODER_REL).jar: apache-maven-$(APACHE_REL)/conf/settings.xml $(SRC)
	env "PATH=apache-maven-$(APACHE_REL)/bin:$$PATH" mvn package


## Download Maven to a local directory, define a local path for repository

apache-maven-$(APACHE_REL)/conf/settings.xml:
	wget $(APACHE_MIRROR)/maven/maven-3/$(APACHE_REL)/binaries/apache-maven-$(APACHE_REL)-bin.tar.gz \
	  --output-document=- \
	| tar -xzf -
	sed -i -e "/<!-- localRepository/s|.*|<localRepository>m2</localRepository> <!--|" $@


.PHONY: test
.SILENT: test

test: target/gerke_decoder-$(GERKE_DECODER_REL).jar grimeton-clip.wav
	declare expected=493b9550236cea0da6565a3886073ee8 && \
	declare md5="$$(bin/gerke-decoder -v -o1 -l 88 \
                 grimeton-clip.wav 2>&1 1>/dev/null | \
                 sed -e '/MD5/!d' -e 's|.* ||' -e 's|\r||')" && \
	if [ $$md5 = $$expected ]; then echo test successful; \
	else echo test failed, expected: $$expected, actual: $$md5; \
             bin/gerke-decoder -v grimeton-clip.wav -o1 -l 88; fi

grimeton-clip.wav:
	rm -f $@
	wget http://privat.bahnhof.se/wb748077/alexanderson-day/$@
