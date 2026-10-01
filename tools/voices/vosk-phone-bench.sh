#!/bin/bash
# Copies Vosk TTS models and the benchmark inputs onto the phone for VoskBenchmarkTest.
#   tools/voices/vosk-phone-bench.sh <vosk-model-tts-ru-0.10-multi dir> <bench dir> [package]
# <bench dir> holds N.ids.bin, N.input.bin, N.bert.bin written by the Python frontend on a PC.
# Android 16 does not let the app read files adb put into its external dir, so they are copied from
# there into the app's private files/vosk with run-as (debuggable builds only) and the copy is removed.
# Install the app first: run-as needs it.
set -e
model="$1"; bench="$2"; pkg="${3:-com.ozvuchka.app.dev}"
adb="${ADB:-adb}"
dest="/sdcard/Android/data/$pkg/files/vosk"
export MSYS_NO_PATHCONV=1  # keep /sdcard paths as they are in Git Bash...
local_path() { if command -v cygpath >/dev/null; then cygpath -w "$1"; else echo "$1"; fi; }  # ...and give adb.exe Windows ones
"$adb" shell mkdir -p "$dest/bench"
"$adb" push "$(local_path "$model/model.onnx")" "$dest/model.onnx"
"$adb" push "$(local_path "$model/bert/model.onnx")" "$dest/bert.onnx"
for f in "$bench"/*.bin; do "$adb" push "$(local_path "$f")" "$dest/bench/"; done
"$adb" shell "run-as $pkg sh -c 'mkdir -p files/vosk/bench && cp $dest/*.onnx files/vosk/ && cp $dest/bench/* files/vosk/bench/ && ls -la files/vosk files/vosk/bench'"
"$adb" shell rm -r "$dest"
