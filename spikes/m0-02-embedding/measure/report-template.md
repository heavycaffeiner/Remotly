# M0-02 measurement report

Fill in during the physical-device run. Every field below must be recorded;
leave no subjective claims.

## Device

- Model:
- Android version / API level:
- SoC:
- RAM:
- Build type (debug/release):
- Lynx version:
- Termux terminal-view commit/version:

## Fixture results

Replay each fixture at least 3 times and record the median.

| Fixture | Size | Throughput (MiB/s) | Dropped frames | Peak RSS delta |
| --- | --- | --- | --- | --- |
| shell-prompt.bin | 232 B | | | |
| tui-redraw.bin | 5.7 KiB | | | |
| burst-1mb.bin | 1 MiB | | | |
| split-utf8.bin | 560 B | | | |
| invalid-utf8.bin | 38 B | | | |
| long-lines.bin | 4 KiB | | | |

## UTF-8 correctness

- split-utf8.bin fed at every byte boundary renders identically to unsplit: yes/no
- replacement characters caused solely by chunking: none/some (count)
- invalid-utf8.bin: crash? later output corrupted? (yes/no)

## Lifecycle

- mount/unmount/remount x 10: crash (yes/no), stale callbacks (yes/no), duplicate input (yes/no)
- memory growth across remounts: bounded/unbounded

## Input latency

- touch-to-byte latency while burst is arriving (median, ms):

## Pass/fail

- throughput >= 10 MiB/s: pass/fail
- input latency < 100 ms: pass/fail
- no visible frame drop during burst: pass/fail
- no unbounded memory growth: pass/fail
- UTF-8 and invalid-byte correctness: pass/fail

Decision (continue / mitigate / reject):
