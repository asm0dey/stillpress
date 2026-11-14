# 02b follow-up results: incremental publish cost

Benchmark: `PublishCostBench` on `blog.xml` (13.6 MB WXR, media limit 0, posts per page 30, per feed 50), H2 in memory, run with

```bash
STILLPRESS_BENCH_WXR=/path/to/blog.xml mise exec -- mvn -q test -Dtest=PublishCostBench -Dsurefire.failIfNoSpecifiedTests=false
```

The full run is the second of two (the first warms up). Each incremental changes the title of the newest post. Times are in ms; the ratio is total ÷ full total.

## Before (commit `d3de2ed`)

| Run | Files | Total | % of full | fragments | snapshot | link untouched | items | listing pages | feeds | comments feed | static+media | activate | record |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| full | 908 | 992 | 100 | 0 | 22 | – | 94 | 164 | 694 | 8 | 0 | 0 | 1 |
| incremental 1 | 23 | 191 | 19.3 | 0 | 8 | 31 | 2 | 6 | 128 | 8 | 0 | 0 | 1 |
| incremental 2 | 23 | 221 | 22.3 | 0 | 13 | 29 | 1 | 7 | 129 | 8 | 0 | 25 | 1 |
| incremental 3 | 23 | 215 | 21.7 | 0 | 6 | 26 | 1 | 5 | 129 | 7 | 0 | 33 | 1 |

Largest incremental phase: **feeds**, 128–129 ms of 191–221 ms (about 60–67 %). Feeds are also 70 % of the full run.

## After (commit `f7d29cb`, cached feed items)

| Run | Files | Total | % of full | fragments | snapshot | link untouched | items | listing pages | feeds | comments feed | static+media | activate | record |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| full | 908 | 548 | 100 | 1 | 28 | – | 123 | 181 | 189 | 18 | 0 | 0 | 1 |
| incremental 1 | 23 | 86 | 15.7 | 0 | 13 | 37 | 1 | 6 | 10 | 10 | 0 | 0 | 1 |
| incremental 2 | 23 | 117 | 21.4 | 0 | 7 | 36 | 2 | 8 | 12 | 11 | 0 | 34 | 1 |
| incremental 3 | 23 | 104 | 19.0 | 0 | 7 | 35 | 1 | 7 | 10 | 11 | 0 | 25 | 0 |

- **Target (< 10 % of full): not met.** A one-post publish is down from 191–221 ms to 86–117 ms. But the full publish also fell from 992 to 548 ms, because a full run now renders each post's feed item once instead of once per feed. So the ratio only moved from 19–22 % to 16–21 %. Against the old full time the incremental is 9–12 %.
- **Feeds:** 128 → 10–12 ms per incremental.
- **Largest remaining incremental phases:**
  - `link untouched`, 35–37 ms: walking the live release and hard-linking about 885 unchanged files.
  - `activate`, 0–34 ms: after the swap, `ReleaseSwitcher.prune` deletes the oldest release (about 900 files) once more than 3 exist. That's housekeeping after the site is already live.
  - Without the prune, an incremental is 75–86 ms, which is 14–16 % of full.

## After Tasks 6–8 (commit `498529c`)

The changes: the prune runs after the publish has finished, `link untouched` is split into `walk` and `link` with one mkdir per directory, and the comments feed is carried over when the edited post has no comments. `Pruned` is the prune that now runs after the publish; it isn't in Total.

| Run | Files | Total | % of full | fragments | snapshot | walk | link | items | listing pages | feeds | comments feed | static+media | activate | record | pruned after |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| full | 908 | 523 | 100 | 1 | 26 | – | – | 109 | 156 | 212 | 10 | 0 | 0 | 1 | 0 |
| incremental 1 | 22 | 82 | 15.7 | 0 | 15 | 11 | 21 | 2 | 5 | 17 | 0 | 0 | 0 | 1 | 0 |
| incremental 2 | 22 | 75 | 14.3 | 0 | 6 | 9 | 32 | 1 | 8 | 12 | 0 | 0 | 0 | 0 | 32 |
| incremental 3 | 22 | 54 | 10.3 | 0 | 5 | 7 | 17 | 1 | 7 | 10 | 0 | 0 | 0 | 0 | 28 |

- **Target (< 10 % of full): not met.** The best run is 10.3 %, the first 15.7 %. Over the whole follow-up, a one-post publish went from 191–221 ms to 54–82 ms, and a full publish from 992 to 523 ms.
- **Largest phase left:** `link`, 17–32 ms. That's the ~885 hard-link calls themselves. Linking in parallel was measured in Task 7 and was slower (31–45 ms).
- **Next candidates (hardening backlog):**
  - Carry unchanged directories over without linking each file. This needs a different release layout.
  - Narrow `snapshot` (5–15 ms), which reads every published summary and term.
  - `feeds`, 10–17 ms: concatenating fragments for 4–6 feeds of 50 items.
