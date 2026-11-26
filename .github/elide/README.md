# Elide's CI for this fork

This repository is a fork of [oracle/graalpython](https://github.com/oracle/graalpython). Everything
the fork adds to CI is in this folder and in workflow files named `elide-*.yml`. GitHub only reads
workflows from `.github/workflows`, so the workflow files cannot live here.

The fork does not edit upstream's files, so merging from upstream cannot conflict with its CI. The
one exception is listed at the end.

## Workflows

| Workflow | Runs on | What it does |
|---|---|---|
| `elide-ci.yml` | pull requests that are not drafts, pushes to `master` and `elide-*.x` | Upstream's tests of the native standalone, on Linux amd64 |

The jobs are upstream's, read from `ci.jsonnet`. The selection is `ci-jobs.txt`. To run another
upstream job, add a line there. `ci-plan` turns the file into the job filter and fails when a line
no longer matches a job.

The fork is about the native build of GraalPy, so only jobs that test the native standalone are
selected: no style checks, no tests on the JVM standalone and no Java unit tests.

Two jobs are written out in `elide-ci.yml` instead of selected, because upstream's versions do not
work on GitHub. The comments in the workflow say why.

- The build of the native standalone that all tests run on.
- GraalPy's own Python tests, including the C API tests, on the native standalone.

## Runners

All jobs run on Elide's runners, the pool with the label `linux-amd64-cipool`. Set the repository
variable `ELIDE_CI_RUNNER` to use another label.

Upstream's workflow for running the jobs, `ci-matrix-gen.yml`, names GitHub's hosted runners.
`elide-ci.yml` therefore calls `elide-ci-matrix.yml`, a copy that `ci-matrix-sync` generates from
upstream's file with three changes: the runner, a first step that empties the workspace, and Python
from `uv` instead of `actions/setup-python`. The runners are on Ubuntu 26.04, for which that action
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
