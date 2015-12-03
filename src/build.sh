#!/usr/bin/env bash

cd "$(dirname "$0")"

BUILD_DIR="../build"
rm -rf "$BUILD_DIR"

SRC_DIRS=("devices" "smartapps")
for src_dir in "${SRC_DIRS[@]}"
do
    mkdir -p "$BUILD_DIR/$src_dir"
    for file in $(ls -1 $src_dir/*.m4*)
    do
        dest_file=$(echo -n $file | sed 's/\.m4//')
        m4 "$file" > "$BUILD_DIR/$dest_file"
    done
done

echo "Generated output files in $(readlink -f $BUILD_DIR)"
