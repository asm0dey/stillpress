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
