# Synthetic 7z fixtures
Generated with official 7-Zip 26.06. These contain synthetic bytes, not media or account data.
The public test password is `fixture-only`.

Input: `a.nfo` contains `fixture metadata ` repeated 19 times. `movie.mkv` contains
bytes 1A 45 DF A3 followed by 131073 bytes with value `(i * 31 + i / 257) % 256`
(integer division, i starting at zero).

Commands: `7zr a encrypted-stored.7z input/* -m0=Copy -ms=on -mhe=on -pfixture-only`
and `7zr a encrypted-compressed.7z input/* -m0=LZMA2 -ms=on -mhe=on -pfixture-only`.
Tests cover encrypted headers, preceding entries, split volumes, random seeks, and cleanup.
