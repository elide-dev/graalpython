# Elide's CI for this fork

This repository is a fork of [oracle/graalpython](https://github.com/oracle/graalpython). Everything
the fork adds to CI is in this folder and in workflow files named `elide-*.yml`. GitHub only reads
workflows from `.github/workflows`, so the workflow files cannot live here.

The fork does not edit upstream's files under `.github`, so merging from upstream cannot conflict
with its CI.

## Workflows

| Workflow | Runs on | What it does |
|---|---|---|
| `elide-ci.yml` | pull requests that are not drafts, pushes to `master` and `elide-*.x` | A selection of upstream's test jobs on Linux amd64 |

`elide-ci.yml` calls upstream's reusable workflow `ci-matrix-gen.yml`, which reads the jobs from
`ci.jsonnet`. The selection is `ci-jobs.txt`. To run another upstream job, add a line there.
`ci_plan.py` turns the file into the job filter and fails when a line no longer matches a job.

The native standalone for the tests is built by `elide-ci.yml` itself. Upstream's job for it
fails on GitHub; the comment in the workflow says why.

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

`ci-matrix-gen.yml` must stay enabled: `elide-ci.yml` calls it.

## After merging from upstream

1. Look at the Actions page for workflows that upstream added. They arrive enabled.
2. If the `plan` job of `elide-ci.yml` fails, upstream renamed or removed a job: update `ci-jobs.txt`.
