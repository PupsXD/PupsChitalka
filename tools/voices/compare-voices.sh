#!/bin/bash
# Voices books with this checkout and with another commit (main by default) and prints every line
# whose voice changed, with context. Books are .epub, .fb2 or .txt (see fetch-wikisource.sh).
#   tools/voices/compare-voices.sh [-r main] book1.epub book2.txt ...
# Output goes to build/voices/: <book>.before, <book>.after, <book>.changes.
# JAVA_HOME must point to a JDK 17+; on this machine: export JAVA_HOME="$(cygpath -w ~/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2)"
set -e
ref=main
if [ "$1" = "-r" ]; then ref="$2"; shift 2; fi
repo="$(cd "$(dirname "$0")/../.." && pwd)"
out="$repo/build/voices"
base="$repo/build/voices-base"
mkdir -p "$out"
test_file=app/src/test/java/com/ozvuchka/app/speech/RealBookSpeakersTest.kt
if [ ! -d "$base" ]; then git -C "$repo" worktree add -q --detach "$base" "$ref"; fi
git -C "$base" checkout -q --detach "$ref"
cp "$repo/local.properties" "$base/" 2>/dev/null || true
cp "$repo/$test_file" "$base/$test_file"
voice() { # checkout, book, target
    (cd "$1" && REAL_BOOK="$2" REAL_BOOK_ROLES="$3" ./gradlew.bat :app:cleanTestDebugUnitTest :app:testDebugUnitTest \
        --console=plain --tests '*RealBookSpeakersTest*' -i 2>&1 | grep -E "Lines:|FAILED|^e: " || true)
}
for book in "$@"; do
    name="$(basename "${book%.*}")"
    book="$(cd "$(dirname "$book")" && pwd)/$(basename "$book")"
    echo "== $name"
    echo -n "  $ref: "; voice "$base" "$book" "$out/$name.before"
    echo -n "  this: "; voice "$repo" "$book" "$out/$name.after"
    echo -n "  changed: "
    perl -CSD "$repo/tools/voices/roles-diff.pl" "$out/$name.before" "$out/$name.after" > "$out/$name.diff"
    perl -CSD "$repo/tools/voices/roles-context.pl" "$out/$name.diff" "$out/$name.after" > "$out/$name.changes"
done
echo "Context of every change: $out/<book>.changes; remove the other checkout with: git worktree remove $base"
