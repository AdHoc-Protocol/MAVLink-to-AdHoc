#!/usr/bin/env bash
# Downloads the MAVLink dialect XMLs from upstream master into msgs/.
set -eu
cd "$(dirname "$0")"
mkdir -p msgs && cd msgs
BASE=https://raw.githubusercontent.com/mavlink/mavlink/master/message_definitions/v1.0
for f in ASLUAV AVSSUAS all ardupilotmega common csAirLink cubepilot development \
         icarous loweheiser marsh minimal paparazzi python_array_test standard \
         stemstudios storm32 test uAvionix; do
    curl -sSf -o "${f}.xml" "$BASE/${f}.xml" && echo "  ${f}.xml" || echo "  FAILED ${f}.xml" >&2
done
