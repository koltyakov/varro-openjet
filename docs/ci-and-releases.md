# CI and releases

## Workflows

GitHub Actions separates validation from release publishing:

- [CI](../.github/workflows/ci.yml) runs on pull requests, pushes to `main`, and manual dispatch. It lints workflows, Python release helpers and shell syntax, tests the build/release scripts, then runs TypeScript checking, vendor integrity, Kotlin/browser tests and packaging. The exact pinned published OpenCode V2 binary is enabled in that same Gradle invocation, so the released adapter and managed-service suites run without a second Kotlin compilation or test pass. Successful attempts save `plugin-archive-<attempt>` for 14 days. CI does not run the IDE verifier or publish anything.
- [Release](../.github/workflows/release.yml) starts for `v*` tag pushes, or resumes tagged releases after successful main CI completion. It selects successful CI for the tag's exact commit, downloads that attempt's immutable archive, verifies it across all supported IDEs, then publishes that same ZIP. Tag jobs never rebuild or rerun the test suite. Both workflows pin `ubuntu-24.04` rather than following runner OS migrations.

The five IDE verifier jobs can run concurrently, subject to available GitHub runners. Each job forces only `verifyPlugin` to rerun with Gradle's task-scoped `--rerun`, so verification cannot reuse an old verdict while prerequisites can reuse cached work. Runner setup, Gradle configuration and IDE dependency downloads still add overhead to the verifier itself.

## Tag a release

To release, first set `pluginVersion` in `gradle.properties`, commit it with the changes and push to `main`. Tag that same commit with the matching version:

```bash
git tag v0.9.4 <commit-tested-by-CI>
git push origin v0.9.4
```

Use tags such as `v0.9.4` for stable releases or `v0.9.4-rc.1` for prereleases. The tag must match both the committed `pluginVersion` and the packaged plugin descriptor. There is no tag-time version override. The archive's plugin ID and filename are checked too, and every verifier/publisher checks its SHA-256 against the selected CI archive.

## Early tags and retries

You may push the tag while CI is running. The tag workflow checks once, then exits if CI is not ready. No runner polls or sleeps waiting for CI. When CI completes successfully, a `workflow_run` handler checks for the matching version tag and dispatches the tag-scoped release. If CI finishes before the tag exists, the later tag push starts the release directly. The handler accepts only successful push/manual CI on this repository's `main`, never PR/fork code. The workflow must be committed on the default branch for completion events and dispatches to work.

A failed or cancelled latest CI run cannot fall back to an older green run. Fork/PR artifacts, other commits, earlier rerun attempts and missing or expired archives are rejected. If CI failed or its archive expired, rerun **all jobs** of the matching main CI run. Its successful completion can restart the tagged release. Tags moved after the release workflow starts are rejected before verification or publishing. Do not move an existing release tag to work around a mismatch.

Tag runs are serialized by tag. Before starting the IDE matrix, the release reserves an unpublished GitHub draft containing the owning workflow run ID and commit. A second overlapping event cannot repeat verification or publishing, even if the first attempt fails. Retry **the original run's failed jobs**, not a new release run. An explicit rerun of the original workflow can resume its own draft. Existing manual drafts and published releases are never taken over automatically. No draft is created until CI succeeds and the downloaded archive passes identity checks.

After all five IDE verifications pass, the owning run generates release notes, attaches `varro-openjet-<version>.zip` and publishes the draft. Prerelease suffixes produce GitHub prereleases. Draft reservation and GitHub publishing receive `contents: write`; the CI completion handler has only the `actions: write` permission needed to dispatch the release, and IDE verification stays read-only. No publication or Marketplace upload is automatically retried after an uncertain result. If branch protection requires the old separate compatibility check, update it to require the combined **Test and package plugin** CI job.

## JetBrains Marketplace

Stable releases also upload the same verified ZIP to [Varro: OpenJet on JetBrains Marketplace](https://plugins.jetbrains.com/plugin/34204-varro-openjet), after all checks and GitHub publication succeed. Prerelease tags remain GitHub-only. CI never publishes directly; a matching existing release tag is required. To restart a deferred release manually, use **Run workflow** on Release and select its tag, not a branch.

Before pushing the first stable tag with Marketplace publishing enabled:

1. Generate a permanent token in [Marketplace > My profile > Tokens](https://plugins.jetbrains.com/author/me/tokens), using the account that can upload updates to the plugin.
2. In [GitHub > Settings > Secrets and variables > Actions](https://github.com/koltyakov/varro-openjet/settings/secrets/actions), add a repository secret named `JETBRAINS_MARKETPLACE_TOKEN` with that token as its value. Never commit the token.
3. Commit the workflow changes, then push a new, unused version tag, such as `v0.9.3`. Version `0.9.2` has already been uploaded manually, so do not upload it again.

The upload targets plugin ID `34204` and the default Stable channel. A missing token fails the Marketplace job. Marketplace still verifies and reviews each update; a successful upload does not mean it is publicly available yet. Publisher-side signing is not configured by this workflow.

If an upload fails or times out, check the Marketplace Versions page before rerunning the failed job. The upload may already have succeeded, and the workflow deliberately does not retry the POST automatically. Rerun only the failed job once you have confirmed that the version is absent. GitHub releases remain available if the Marketplace upload fails.
