# Elide's CI for this fork

This repository is a fork of [oracle/graalpython](https://github.com/oracle/graalpython). Everything
the fork adds to CI is in this folder and in workflow files named `elide-*.yml`. GitHub only reads
workflows from `.github/workflows`, so the workflow files cannot live here. The package benchmarks
live with GraalPy's other benchmarks, in a folder of their own (see "Package benchmarks").

The fork does not edit upstream's files, so merging from upstream cannot conflict with its CI. The
one exception is listed at the end.

## Branches

| Branch | What it is |
|---|---|
| `master` | A copy of upstream's `master`. Nothing is merged into it; it only moves forward to upstream's commits. |
| `develop` | The fork's main branch and the repository's default branch. Pull requests are merged here. |
| `elide-*.x` | The fork's release branches, each on top of one upstream release. |

Upstream's changes reach `develop` through a pull request that merges `master` into it. Merge that
pull request with a merge commit, never squashed or rebased: a squash drops the link to upstream's
history, and every later merge would conflict with it.

`git diff master develop` shows what the fork changed.

## Workflows

| Workflow | Runs on | What it does |
|---|---|---|
| `elide-ci.yml` | pull requests that are not drafts, pushes to `develop` and `elide-*.x` | Upstream's tests of the native standalone, on Linux amd64 |

The jobs are upstream's, read from `ci.jsonnet`. The selection is `ci-jobs.txt`. To run another
upstream job, add a line there. `ci-plan` turns the file into the job filter and fails when a line
no longer matches a job.

The fork is about the native build of GraalPy, so only jobs that test the native standalone are
selected: no style checks, no tests on the JVM standalone and no Java unit tests.

Two jobs are written out in `elide-ci.yml` instead of selected, because upstream's versions do not
work on GitHub. The comments in the workflow say why.

- The build of the native standalone that all tests run on.
- GraalPy's own Python tests, including the C API tests, on the native standalone.

## Benchmarks

`elide-codspeed.yml` measures the wall time of GraalPy's C API micro benchmarks, its pure Python
meso benchmarks and benchmarks of real packages on a native standalone and reports it to CodSpeed
(https://app.codspeed.io/elide-dev/graalpython). It runs on pushes to `develop` and `elide-*.x`,
and on pull requests that carry the label `perf:bench`. `codspeed-bench` is the driver that runs
the benchmarks and writes the results.

The standalone and the packages' wheels are built on the pool. The measurement runs on the
benchmark runner with the label `linux-amd64-bench`, which is shared between repositories, so only
the measurement runs there.

### Package benchmarks

The suite `packages` of `codspeed-bench` measures pydantic, orjson, pandas, numpy and lxml. Its
files are next to upstream's benchmarks, in the fork's own folder
`graalpython/com.oracle.graal.python.benchmarks/python/packages`, which upstream does not have, so
merges from upstream cannot conflict with it:

| Benchmark | What one iteration does |
|---|---|
| `pydantic-json` | `validate_json` and `dump_json` of a list of nested models with a discriminated union, and `model_dump_json`/`model_validate_json` per model |
| `pydantic-models` | `model_validate` from dicts and `model_dump`, `model_validate_json` and `model_dump_json`, of nested models with a field validator |
| `orjson-roundtrip` | `dumps` and `loads` of a nested document, also indented with sorted keys, and `dumps` of dataclasses |
| `pandas-csv` | `read_csv` of an in-memory CSV (dates, a categorical column), a filter, `to_csv`, a groupby |
| `pandas-str` | `Series.str` methods (strip, lower, contains, extract, split, replace, title) and `to_datetime` |
| `pandas-groupby-merge` | merges of a fact table with two dimension tables, groupby aggregations, `pivot_table`, a grouped rank |
| `pandas-apply` | `DataFrame.apply(axis=1)`, `Series.apply` and `Series.map` with Python functions, `iterrows`, `itertuples` |
| `numpy-small-ops` | a particle simulation on many 3-element arrays from a Python loop |
| `numpy-vectorized` | ufuncs, reductions, sort, percentile, an FFT and a small linear solve on large arrays |
| `lxml-xpath` | parse, XPath queries, edits and serialization of an XML document, and `lxml.html` parsing of a table |

Each file is in the format of upstream's `harness.py`, like the files in `meso` and `micro`:
`__setup__(size)` builds the input from a fixed seed and is not measured, `__benchmark__(size)` is
one measured iteration and calls `measure(size)`. Some benchmarks repeat the work `ROUNDS` times
per iteration on smaller data. That was meant to make their iterations on GraalPy steadier, and it
did not: orjson-roundtrip, pandas-csv, pandas-str, pandas-groupby-merge and lxml-xpath still vary
by up to a factor of ten from one iteration to the next. A diagnose run (see "Diagnosing noisy rows")
traced the variation to garbage collection pauses, which take 40 to 70% of an iteration on these
rows, from two causes in GraalPy's memory management:

- On orjson-roundtrip, pandas-csv, pandas-str and lxml-xpath the heap that survives a full
  collection grows with every iteration (by 20 to 110 MB per iteration), although the benchmarks
  keep nothing between iterations; it is only released after the last iteration. Collections
  therefore take longer and longer (up to 7.6 s), and an iteration is slow when one falls into it.
- On pandas-groupby-merge, native allocations reach GraalPy's native memory limit
  (`--python.InitialNativeMemory`, 256 MB) more often in every iteration, and each time GraalPy
  forces full collections, up to 65 in one iteration, until it doubles the limit. Durations follow
  the iteration number (17 s at iteration 10, 16 s at iteration 22, about 1 s after each doubling).

The rows measure GraalPy's default behaviour, so they keep this variation until it is fixed.

Every iteration (or round) compares its result
with `EXPECTED[size]` in the file and fails the benchmark when it differs; floats are compared with a
relative tolerance, because they differ in the last digits between platforms. Unlike upstream's
`macro` benchmarks, the files do not call `ensure_packages`: the packages are installed before the
run, from the wheels built on the pool.

The fork does not edit upstream's benchmark table, `mx.graalpython/mx_graalpython_bench_param.py`,
so the folder has its own, `benchmarks.txt`: the benchmarks with their harness arguments (iterations
and size). The size is part of the name on CodSpeed, for example
`graalpython/com.oracle.graal.python.benchmarks/python/packages/pandas-csv.py::packages/pandas-csv[200000]`.

The files are written for the fork and carry no copyright header. Upstream's style job, which this
CI does not run (see below), would flag them for the missing Oracle header.

To add a benchmark:

1. Write `<name>.py` in the `packages` folder and add a line to its `benchmarks.txt`. Size it so that an
   iteration takes about 0.2 to 3 s on GraalPy, and give it enough iterations that the second half,
   which is what is reported, is warmed up (40 for the current ones).
2. Run it with CPython, for example
   `python graalpython/com.oracle.graal.python.benchmarks/python/harness.py graalpython/com.oracle.graal.python.benchmarks/python/packages/<name>.py -i 4 <size>`
   with the `python` of a virtual environment that has the folder's `requirements.txt` installed. For a size
   without an entry in `EXPECTED`, the first iteration prints its result and the others are compared
   with it; copy it into `EXPECTED`.
3. `python3 .github/elide/codspeed-bench --python <that environment's python> --suite packages --bench <name>`
   runs it as the workflow does.
4. A new package goes into the folder's `requirements.txt` with all its dependencies pinned.
   A package with compiled code also needs a build line in the `wheels` job of `elide-codspeed.yml`.

The versions are pinned in the folder's `requirements.txt`. The compiled packages are built
from source with GraalPy's pip, which applies GraalPy's patch for the version from
`graalpython/lib-graalpython/patches/metadata.toml`; a new version needs a patch there or none at
all. A version change changes the measurement, so make it in a pull request of its own.

The `wheels` job builds the wheels on the pool with the standalone of the run, every run, without a
cache: numpy without an external BLAS or LAPACK (numpy's own slower fallback routines, which is why
the linear algebra in `numpy-vectorized` is small), pandas against that numpy, lxml with libxml2 and
libxslt linked in statically, pydantic-core and orjson with a Rust toolchain installed into the
job's temporary directory. The pure Python packages come from PyPI as wheels. The job fails when a
wheel needs a shared library other than glibc, libstdc++ and libgcc_s, because the benchmark host
has only its base libraries and nothing can be installed there. `check` installs the wheels without
a network (`package-venv`) and runs every package benchmark for four iterations; `walltime` installs them into a
virtual environment in the job's temporary directory and runs the suite with that environment's
interpreter (`--suite-python`).

### Diagnosing noisy rows

A manual run of `elide-codspeed.yml` with the input `diagnose` set measures without reporting to
CodSpeed. Instead of `walltime`, its job `diagnose` runs the benchmarks selected by the input
`bench` on the benchmark host once per interpreter option set named in `variants`, with the number
of iterations of `iterations`:

```sh
gh workflow run elide-codspeed.yml --ref <branch> -f diagnose=true -f variants=gc-log,default
```

The option sets are the lines of `codspeed-diagnose.txt` (`all` runs every line). `codspeed-diagnose`
runs `codspeed-bench` once per set with the set's options (`--python-arg`) and keeps, in the
artifact `codspeed-diagnose-<attempt>`: every iteration's duration and the peak RSS of each
benchmark process (`<set>/walltime-summary.json`), the complete output of each process with the GC
log (`<set>/<benchmark>.log`), the collections per iteration (`diagnose-summary.json`) and the tables
(`diagnose.md`, also on the run's summary page). `check` starts the interpreter once with every set
first, so that an option the build does not know fails on the pool. `codspeed-bench` refuses
`--python-arg` when it would report to CodSpeed.

## Runners

All jobs run on Elide's runners, the pool with the label `linux-amd64-cipool`. Set the repository
variable `ELIDE_CI_RUNNER` to use another label.

Upstream's workflow for running the jobs, `ci-matrix-gen.yml`, names GitHub's hosted runners.
`elide-ci.yml` therefore calls `elide-ci-matrix.yml`, a copy that `ci-matrix-sync` generates from
upstream's file with four changes: the runner, a first step that empties the workspace, Python
from `uv` instead of `actions/setup-python`, and jobs that stop when their run is cancelled. The runners are on Ubuntu 26.04, for which that action
has no build of the Python versions upstream asks for. Do not edit the copy. The `plan` job fails when it is not what the script would generate.

Elide's runners keep state between jobs, so `elide-ci.yml` does not run for pull requests from
forks. Such a pull request gets no tests until its branch is pushed to this repository.

## Scripts have no file extension

Upstream's style job, which this CI does not run but upstream does, requires an Oracle copyright
header in every `.py`, `.sh`, `.java`, `.c` and `.h` file of the repository. The exceptions are listed in an upstream file, which the fork does not
edit. The scripts in this folder are therefore Python files without the `.py` extension; run them
with `python3 <file>`.

## Upstream workflows that are switched off

These upstream workflows start by themselves and are of no use to the fork. They are disabled in the
repository settings (Actions, select the workflow, "Disable workflow"), not removed, so their files
stay identical to upstream's.

| Workflow | File | Would run on |
|---|---|---|
| Run CI unittests | `ci-unittests.yml` | every pull request |
| Post Merge Actions | `ci-post-merge.yml` | every pull request |
| Build and deploy website | `build-website.yml` | every pull request and push |
| Run Weekly unittest retagger | `ci-unittest-retagger.yml` | Mondays |
| Weekly Downstream Tests | `downstream-tests.yml` | Mondays |
| Test on exotic platforms | `platforms-tests.yml` | Mondays |

## After merging from upstream

1. Look at the Actions page for workflows that upstream added. They arrive enabled.
2. If the `plan` job of `elide-ci.yml` fails at the check of `elide-ci-matrix.yml`, upstream
   changed `ci-matrix-gen.yml`: run `python3 .github/elide/ci-matrix-sync` and commit the result.
3. If it fails at the job selection, upstream renamed or removed a job: update `ci-jobs.txt`.
4. If the merge conflicts in one of the tag files listed below, take upstream's version and remove
   the `linux-x86_64-github` key from the listed test again.

## Tests that are switched off

Upstream's tag files under `graalpython/com.oracle.graal.python.test/src/tests/unittest_tags` say
on which platforms a standard-library test is expected to pass. A test that does not pass in this
CI loses its `linux-x86_64-github` key there. These are the only edits the fork's CI makes to
upstream's files:

| Tag file | Test | Reason |
| --- | --- | --- |
| `test_asyncio.txt` | `test.test_asyncio.test_events.PollEventLoopTests.test_subprocess_close_after_finish` | hangs until the 600 s timeout, on GitHub's runners and on Elide's |
| `test_datetime.txt` | `test.datetimetester.ZoneInfoTest[America/<zone>]_Fast.test_system_transitions` and `..._Pure...` for the 16 zones in Brazil (32 entries) | the runner's time-zone files and GraalPy's time-zone data disagree by one hour on 31 March 1965; fails on most of Elide's runners, cause not found yet |
