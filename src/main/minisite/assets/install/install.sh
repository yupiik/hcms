#! /bin/bash

#
# Copyright (c) 2024-present - Yupiik SAS - https://www.yupiik.com
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#  http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#

set -e

CENTRAL="https://repo.maven.apache.org/maven2"
GROUP_ID="io/yupiik/hcms"
ARTIFACT_ID="hcms"
if [ -z "$HCMS_DIR" ]; then
    HCMS_DIR="$HOME/.yupiik/hcms"
fi

echo ''
echo '  __     __             _  _  _'
echo '  \ \   / /            (_)(_)| |'
echo '   \ \_/ /_   _  _ __   _  _ | | __'
echo '    \   /| | | || '"'"'_ \ | || || |/ /'
echo '     | | | |_| || |_) || || ||   <'
echo '     |_|  \__,_|| .__/ |_||_||_|\_\'
echo '                | |'
echo '                |_|'
echo '  _    _     _____     __  __      _____'
echo ' | |  | |   / ____|   |  \/  |    / ____|'
echo ' | |__| |  | |        | \  / |   | (___'
echo ' |  __  |  | |        | |\/| |    \___ \'
echo ' | |  | | _| |____  _ | |  | | _  ____) |_'
echo ' |_|  |_|(_)\_____|(_)|_|  |_|(_)|_____/(_)'
echo '                        Installing...'

#
# check our pre-requisites
#

uname_value="$(uname)"
if [[ "$uname_value" != "Linux" ]]; then
	echo "$uname_value not yet supported for native mode, please follow JVM mode installation or build it from sources."
	exit 1
fi
if [ -z $(which curl) ]; then
	echo "Curl not found, ensure you have it on your system before using that script please."
	exit 2
fi
if [ -z $(which grep) ]; then
	echo "Grap not found, ensure you have it on your system before using that script please."
	exit 3
fi
if [ -z $(which sed) ]; then
	echo "Sed not found, ensure you have it on your system before using that script please."
	exit 4
fi
if [ -z $(which head) ]; then
	echo "Head not found, ensure you have it on your system before using that script please."
	exit 5
fi

#
# install
#

echo "Ensuring $HCMS_DIR exists..."
mkdir -p "$HCMS_DIR/bin"

base="$CENTRAL/$GROUP_ID/$ARTIFACT_ID"
last_release="$(curl --fail  --silent "$base/maven-metadata.xml" -o - | grep latest | head -n 1 | sed 's/.*>\([^<]*\)<.*/\1/')"
binary="$HCMS_DIR/bin/hcms"

echo "Downloading yupiik HCMS..."
curl --fail --location --progress-bar "$base/$last_release/$ARTIFACT_ID-$last_release-Linux-amd64.bin" > "$binary" && \
  chmod +x "$binary"

echo -e "\n\n\nHCMS installed!\nYou can now add $HCMS_DIR/bin to your PATH variable (in your ~/bashrc or so).\n\n"

