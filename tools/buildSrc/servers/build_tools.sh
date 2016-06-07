#!/bin/bash
# Expected arguments:
# $1 = out_dir
# $2 = dist_dir
# $3 = build_number
# $4 = number of --parallel-thread (optional)

set -e

PROG_DIR=$(dirname "$0")
CURRENT_OS=$(uname | tr A-Z a-z)

function die() {
  echo "$*" > /dev/stderr
  echo "Usage: $0 <out_dir> <dest_dir> <build_number> [num_threads=47]" > /dev/stderr
  exit 1
}

while [[ -n "$1" ]]; do
  if [[ -z "$OUT_DIR" ]]; then
    OUT_DIR="$1"
  elif [[ -z "$DIST_DIR" ]]; then
    DIST_DIR="$1"
  elif [[ -z "$BNUM" ]]; then
    BNUM="$1"
  elif [[ -z "$NUM_THREADS" ]]; then
    NUM_THREADS="$1"
  else
    die "[$0] Unknown parameter: $1"
  fi
  shift
done

if [[ -z "$OUT_DIR"  ]]; then die "## Error: Missing out folder"; fi
if [[ -z "$DIST_DIR" ]]; then die "## Error: Missing destination folder"; fi
if [[ -z "$BNUM"     ]]; then die "## Error: Missing build number"; fi

if [[ "$OUT_DIR" != /* ]]
then
    pushd "$PROG_DIR"/../../..
    OUT_DIR="$PWD/$OUT_DIR"
    popd
fi

TARGET="dist makeSdk zipOfflineRepo"
if [[ $CURRENT_OS == "linux" ]]; then
    TARGET="$TARGET makeWinSdk"
fi

cd "$PROG_DIR"

GRADLE_FLAGS="--no-daemon --info"

# first build Eclipse/Monitor
( set -x ; OUT_DIR="$OUT_DIR" DIST_DIR="$DIST_DIR" BUILD_NUMBER="$BNUM" ../../gradlew -b ../../build.gradle $GRADLE_FLAGS init publishLocal ) || exit $?
( set -x ; OUT_DIR="$OUT_DIR" DIST_DIR="$DIST_DIR" BUILD_NUMBER="$BNUM" ../../gradlew -b ../../../sdk/eclipse/build.gradle $GRADLE_FLAGS copydeps buildEclipse ) || exit $?

# temp disable --parallel builds
#OUT_DIR="$OUT_DIR" DIST_DIR="$DIST_DIR" ../../gradlew -b ../../build.gradle --parallel-threads="${NUM_THREADS:-47}" $GRADLE_FLAGS makeSdk
( set -x ; OUT_DIR="$OUT_DIR" DIST_DIR="$DIST_DIR" BUILD_NUMBER="$BNUM" ../../gradlew -b ../../build.gradle $GRADLE_FLAGS $TARGET ) || exit $?

# Generate repository XML metadata for release script

LATEST_REPO_XSD=$(ls -1 ../../base/sdklib/src/main/java/com/android/sdklib/repository/sdk-repository-*.xsd | sort -r | head -n 1)

SOURCE_PROPS=$PWD/../../../sdk/files/tools_source.properties
ZIPS=""
for OS in linux windows darwin; do
    if [[ $OS == $CURRENT_OS || ( $CURRENT_OS == linux && $OS == windows ) ]]; then
        ZIP="sdk-repo-$OS-tools-$BNUM.zip"
        ZIPS="$ZIPS $OS $DIST_DIR/$ZIP:$ZIP"

        # Package source.properties in the zip, it currently lacks it
        ( set -x
          cd $DIST_DIR
          cp $SOURCE_PROPS source.properties
          zip -9r $ZIP source.properties
          rm source.properties
        )

        # We expect a "tools" folder in the zip file
        ( set -x
          cd $DIST_DIR
          rm -rf tools
          mkdir tools
          cd tools
          unzip -q ../$ZIP
          cd ..
          rm $ZIP
          zip -9rq $ZIP tools
          rm -rf tools
        )
    fi
done

( set -x ; ./mk_sdk_repo_xml.sh $DIST_DIR/repository.xml $LATEST_REPO_XSD tools $ZIPS )


