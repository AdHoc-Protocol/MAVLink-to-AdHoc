#!/usr/bin/env bash
# Compiles the converter and regenerates AdHoc/<dialect>.cs from msgs/.
set -eu
cd "$(dirname "$0")"
javac -encoding UTF-8 --release 17 -d out src/org/unirail/MavLink2AdHoc.java
java -Dfile.encoding=UTF-8 -cp out org.unirail.MavLink2AdHoc msgs AdHoc
