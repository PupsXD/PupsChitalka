#!/bin/bash
# Downloads a public-domain text from ru.wikisource and saves it as plain text.
#   tools/voices/fetch-wikisource.sh "Дама_с_собачкой_(Чехов)" out.txt
#   tools/voices/fetch-wikisource.sh "Попрыгунья_(Чехов)" out.txt "Глава_I Глава_II Глава_III"
# The third argument lists subpages for works split into chapters.
set -e
title="$1"; out="$2"; parts="$3"
here="$(cd "$(dirname "$0")" && pwd)"
raw() { curl -s -m 30 -G "https://ru.wikisource.org/w/index.php" --data-urlencode "title=$1" --data-urlencode "action=raw"; echo; }
if [ -z "$parts" ]; then raw "$title"; else for p in $parts; do raw "$title/$p"; done; fi > "$out.wiki"
perl -CSD "$here/wiki2txt.pl" "$out.wiki" > "$out"
rm "$out.wiki"
echo "$out: $(wc -l < "$out") paragraphs, $(grep -c '^[—–-]' "$out") start with a dash"
