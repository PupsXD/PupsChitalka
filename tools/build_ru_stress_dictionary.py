"""Build the compact, unambiguous RUAccent word list shipped with the app.

Download the three dictionary/*.json.gz files from ruaccent/accentuator and run:
  python tools/build_ru_stress_dictionary.py accents.json.gz omographs.json.gz yo_homographs.json.gz

The input hashes pin the source data. The output is a sorted index of lowercase
UTF-8 words, each with one stressed vowel position (no neural models at runtime).
"""

import argparse
import gzip
import hashlib
import json
import struct
from pathlib import Path


SOURCE = "https://huggingface.co/ruaccent/accentuator/tree/main/dictionary"
HASHES = (
    "aa460ebba90de00fbbf3d41d121961f605b98667e45efb7920f127473b15515e",
    "04a9e81c68d65f65ba493fe0110f99e79087548c2beeec3032e2b66e28706f36",
    "c4ee777bbbab87f9eac838f370ad92974e079d02b21903e480c54b5f0c8c60d1",
)
VOWELS = set("аеёиоуыэюя")
MAX_LETTERS = 9


def read_source(path: Path, expected_hash: str):
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest != expected_hash:
        raise ValueError(f"Unexpected SHA-256 for {path}: {digest}")
    with gzip.open(path, "rt", encoding="utf-8") as stream:
        return json.load(stream)


def build(accents_path: Path, omographs_path: Path, yo_homographs_path: Path, output: Path):
    accents = read_source(accents_path, HASHES[0])
    omographs = read_source(omographs_path, HASHES[1])
    yo_homographs = read_source(yo_homographs_path, HASHES[2])

    # Plain е can stand for ё; leave those cases to RuVoice's contextual model.
    ambiguous = {word.replace("ё", "е") for word in omographs}
    ambiguous.update(word.replace("ё", "е") for word in yo_homographs)
    has_yo_form = {word.replace("ё", "е") for word in accents if "ё" in word}

    records = []
    for word, spoken in accents.items():
        if not (2 <= len(word) <= MAX_LETTERS and all("а" <= c <= "я" or c == "ё" for c in word)):
            continue
        if sum(c in VOWELS for c in word) < 2 or word.replace("ё", "е") in ambiguous:
            continue
        if "ё" not in word and word in has_yo_form:
            continue
        if spoken.count("+") != 1 or spoken.replace("+", "") != word:
            continue
        stress = spoken.index("+")
        if stress >= len(word) or word[stress] not in VOWELS:
            continue
        records.append((word.encode("utf-8"), stress))

    records.sort(key=lambda record: record[0])
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("wb") as stream:
        stream.write(b"RSTD")
        stream.write(struct.pack("<I", len(records)))
        offset = 0
        for word, _ in records:
            stream.write(struct.pack("<I", offset))
            offset += 2 + len(word)
        for word, stress in records:
            stream.write(bytes((len(word), stress)))
            stream.write(word)
    print(f"{len(records):,} words, {output.stat().st_size:,} bytes: {output}")
    print(f"Source: {SOURCE}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("accents", type=Path)
    parser.add_argument("omographs", type=Path)
    parser.add_argument("yo_homographs", type=Path)
    parser.add_argument("--output", type=Path, default=Path("app/src/main/assets/ru_stress.bin"))
    args = parser.parse_args()
    build(args.accents, args.omographs, args.yo_homographs, args.output)
