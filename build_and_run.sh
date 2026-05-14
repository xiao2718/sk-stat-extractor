#!/usr/bin/env bash
# Build and run the .dat -> .xml dumper using the game's own jars as classpath.
set -euo pipefail

SK="${SK:-/home/alois/.local/share/Steam/steamapps/common/Spiral Knights}"
HERE="$(cd "$(dirname "$0")" && pwd)"

# Classpath = every .jar in the game's code/ directory (skip backups/lwjgl
# natives we don't need at runtime, but globbing everything is harmless for
# compilation/execution of a pure-JVM dump).
CP="$HERE"
for j in "$SK/code/"*.jar; do
    case "$j" in
        *.bak_*|*-natives-*) continue ;;
        *"("*) continue ;;   # skip "projectx-pcode (1).jar" backups
        *-new.jar) continue ;;
    esac
    CP="$CP:$j"
done

echo "Compiling DumpDats.java..."
javac -cp "$CP" -d "$HERE" "$HERE/DumpDats.java"

echo "Running DumpDats..."
java -cp "$CP" DumpDats "$SK/rsrc" "$HERE/out"

echo
echo "Done. Output XMLs in $HERE/out/"
ls -lh "$HERE/out/"
