# MHI2Q Plugin SDK — Plan 3: mibpkg distribution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Plugins and the SDK runtime install, update, roll back and uninstall through mibpkg. Permissions are approved at install time, plugins hot-load into a running SDK, and `/mnt/app` stays writable for as long as any writer needs it.

**Architecture:**
- **mibpkg owns every on-unit write the SDK needs.** It gains two declarative package kinds, keyed by a manifest table:
  - `[sdk]`: the runtime jars, the generated bundle list, and stock-file backup and restore;
  - `[plugin]`: one checked jar, a permission list, the grants line and the reload marker.
- **The SDK repo supplies data, not procedure.** Gradle builds the `.mpk` files with `mibpkg build`. They carry the jars, M7 stamps, the permission catalog (`permissions.json`) and the `[sdk]` values: configurator, bundle line, supported HMI and API version.
- **`installer/mibsdk-dev` becomes a thin dev transport.** It copies a file into the unit's `/tmp` over SSH and calls a mibpkg verb. `log`, `status` and `restart` stay dev-only.
- **One install implementation.** `gen_bundles.py` and the bash install/uninstall logic are ported to Rust, and the Python and bash copies are then deleted.
- **Approval happens in the CLI and the web UI.** Since slim-GEM, GEM only installs mibpkg itself, so it has no package install for which to ask approval (spec §7: "whatever mibpkg UI did the install").
- **`/mnt/app` read-write is refcounted across processes**, through holder files in `/tmp` and a short remount lock. A mibpkg process goes read-only only when no live holder remains. This fixes bench-e2e F8 at its root.

**Tech Stack:**
- mibpkg: Rust 2021, crate deps unchanged (anyhow, serde/serde_json, toml, sha2, semver, flate2; tempfile for tests). Cross-built for QNX 6.5 armv7 with `scripts/build.sh` (Docker). Web front-end is plain JS with `node --test`.
- SDK: Gradle 8.13 build-logic (Java 17), JUnit 5.10.2, Python 3 (host-side `mibsdk-dev` tests only), bash.

**Spec:**
- `docs/superpowers/specs/2026-10-18-mib2q-plugin-sdk-design.md`: §10 (G8), §7 (permissions), §5.3 (packaging and paths), §6.1 (ledger), §13 (carplay-rgi guard).
- mibpkg's own design: `~/complex-projects/mibpkg/docs/superpowers/specs/2026-09-26-mibpkg-design.md`.
- Context: `docs/bench-e2e.md` (F8), `docs/car-readiness.md` (M7), and the Global Constraints of `docs/superpowers/plans/2026-10-18-mibsdk-foundation.md`, all of which apply here.

**Repos, branches, order:**
- **mibpkg** (`~/complex-projects/mibpkg`, on `main` at `ee246bc`, clean when this plan was written):
  - Create a worktree on a new branch `feat/mibsdk-packages` (superpowers:using-git-worktrees). Tasks 1–11 commit there.
  - mibpkg's repo-local git identity is complex256's GitHub noreply address. Keep it; don't reconfigure.
- **mib2q-plugin-sdk** (on `main` at `15c8203`): create a worktree on `feat/mibpkg-distribution`. Tasks 12–14 commit there. Author `complex256 <me@complex256.dev>`.
- **Order:**
  - Tasks 1–11 (mibpkg) and Tasks 12–13 (SDK) are independent and may run in parallel lanes.
  - Task 14 needs the CLI from Task 9.
  - Task 15 (bench) needs everything.
- **Merging:**
  - Merge mibpkg first, as 0.8.0.
  - Then merge the SDK branch: its `mibsdk-dev` requires mibpkg ≥ 0.8.0 on the unit.
  - Don't publish mibpkg 0.8.0 to the official online collection until Task 15 passes on the bench.

**Dependency D1 (car-readiness plan, `docs/superpowers/plans/2026-10-20-car-readiness-gates.md`, M7).** That plan defines the build stamp that proves a jar went through the checks. This plan consumes the stamp with exactly this interface. If that plan lands a different shape, only `mibpkg::mibsdk::stamp::parse` (Task 4) and `StampFiles` (Task 13) adapt.
- A sidecar `<jar>.stamp` sits next to each checked jar in the build output (`build/mibsdk/<id>.jar.stamp`, `dist/build/dist/<name>.jar.stamp`). It is ASCII, one `key=value` per line, LF, and unknown keys are ignored:
  ```
  format=1
  artifact=<jar file name>
  sha256=<lowercase hex SHA-256 of the jar bytes>
  kind=plugin|sdk
  checks=<comma list>        plugin: classVersion48,linkCheck,permissionScan   sdk: classVersion48,distLinkCheck
  api=1
  id=<plugin id>             plugin only
  unrestricted=true|false    plugin only
  ```
- Producers: `mibPluginJar` (plugins) and `:dist:assembleUnit` (the three SDK jars).
- If D1 hasn't landed when Task 13 starts, Task 13 adds the producer itself (step 13.1). In that case, put a note in the car-readiness plan, under M7, saying its stamp already exists.
- M7's "`mibsdk-dev` refuses anything else" is enforced by mibpkg's `stamp::verify`, because `mibsdk-dev` now delegates every install. A host-side pre-check in `mibsdk-dev` is optional and must call the same rules (D1), not re-derive them.

## Global Constraints

Everything in the foundation plan's Global Constraints still holds. In particular:
- class-file v48;
- never log or read the j9 command line;
- `/tmp` is `/dev/shmem`: files only, no directories;
- the unit has no `date`, `tr`, `md5sum`, `cksum`, `find` or python;
- bench SSH needs `-o HostKeyAlgorithms=ssh-rsa -o PubkeyAcceptedKeyTypes=ssh-rsa`.

The rest are specific to this plan:

**On-unit paths**
- **SDK install root**: `/mnt/app/eso/hmi/lsd/mibsdk/`. It holds:
  - `sdk.properties`, `grants.properties`, `SAFE_MODE`;
  - `plugins/<id>.jar`, `plugins/<id>.reload`;
  - `permissions.json` (new; copied by the SDK package).
- **Persist root**: `/mnt/persist/mibsdk/`, holding `boot.state`, `ledger.properties` and `data/<id>.properties`. It is writable without a remount.
- **RAM root**: `/ramdisk/mibsdk/`.
- **SDK jars**: `/mnt/app/eso/hmi/lsd/jars/{mibsdk-runtime,mibsdk-adapter-hqc,mibsdk-rt-retrotranslator}.jar`.
- **Bundle list**: `/mnt/app/eso/hmi/lsd/bundles_dsitracer_enabled.properties`. Marker: `/mnt/app/eso/hmi/lsd/dsitracer_activated`. Tracer jar: `/mnt/app/eso/hmi/lsd/DSITracer.jar`.
- **HMI version**: the `HMI = …` line of `/ifs/version_info.txt`, compared as an exact string after removing whitespace. Supported: `HMI_AU_MIB2_HQC_NAR_G22_C10_180803`.
- **Log**: `/tmp/mibsdk.log`. The runtime's dev channel polls every `dev.poll.ms` (default 2000).

**Paths in mibpkg code**
- Every mibpkg path goes through `Layout::p(abs)`, so tests run under a temp root.
- New constants live in `src/mibsdk/mod.rs`. Nothing else in mibpkg names an SDK path.

**Package identity**
- The SDK package is named `mibsdk`.
- A plugin package's `[package].name` **is** the plugin id.
- A plugin id must satisfy both rules:
  - mibpkg's `valid_name`: 1–40 characters of `[a-z0-9-]`, first character a letter or digit;
  - the runtime's `[a-z0-9][a-z0-9-]{1,40}`.

  Together that means 2–40 characters, first `[a-z0-9]`, the rest `[a-z0-9-]`. The id `mibsdk` is reserved.
- Versions are semver (mibpkg rule). The reference plugins' `plugin.json` versions become `1.0.0`.

**Grants**
- The grants file is `grants.properties`: one line `<id>=<perm>,<perm>` per plugin, parsed by Java `Properties` in `Grants.load`.
- A permission string matches `[A-Za-z0-9._-]+`.
- mibpkg rewrites only the target plugin's line and keeps every other line byte for byte.

**Mount discipline**
- Every write to `/mnt/app` happens under `ctx.mounts.rw("/mnt/app")`. Nothing in mibpkg runs a bare `mount`.
- **Remount lock**: `/tmp/mibpkg.rwlock`.
- **Holder files**: `/tmp/mibpkg.rw.<tag>.<pid>`, where `<tag>` is `app` for `/mnt/app` and `system` for `/mnt/system`.
- Package names `rw` and `rwlock` are reserved.

**Approval and data**
- **No silent grants**: a plugin install or update that adds a permission needs an explicit approval list that covers it. Seed installs and `update-all` never approve on the user's behalf.
- **Plugin data**: plain remove **keeps** `/mnt/persist/mibsdk/data/<id>.properties`, and `--purge` deletes it. This matches mibpkg's `remove` (config kept unless `--purge`) and spec §5.3. **[my call]**

**Dependencies, version and authorship**
- **No new crates** in mibpkg **[my call]**: the existing deps cover TOML, JSON, SHA-256 and semver.
- mibpkg's version becomes `0.8.0` in `Cargo.toml` (Task 11).
- **No personal names or paths in committed files.** Use `~`/`$HOME` for home paths.

## Review Focus

1. **Another mibpkg process ends its write mid-install.** The web server's `gem-screens` redraw, `record_result` after a GEM action, or `online-check` storing its cache each finish their own rw section while a plugin install is still writing. `/mnt/app` must stay writable until the last live holder releases it. A holder left by a crashed process must not pin it read-write forever. **Tests:** Task 1, `rwhold::other_live_holder_keeps_app_rw` and `rwhold::dead_holder_is_ignored_and_swept`.
2. **Update after a revoke.** An update adds `vehicle.parking.write`, and the user had earlier revoked `vehicle.read` by editing `grants.properties`. Only the new permission is asked for, and `vehicle.read` stays revoked. **Test:** Task 8, `plugin_update_asks_only_new_permissions_and_keeps_revocations`.
3. **First mibpkg SDK install on a unit set up by the old `mibsdk-dev`** (the bench). The live bundles file is already the generated one. mibpkg must adopt `mibsdk/stock/` as the backup and never back up the modified file as "stock". **Test:** Task 7, `sdk_install_adopts_mibsdk_dev_stock_backup`.
4. **Purge while the SDK is running.** The plugin's `onDisable` flushes its storage, so the data file could reappear after deletion. The file is deleted only after the runtime confirms the unload; without a confirmation it is kept and the result says so. **Test:** Task 8, `plugin_purge_waits_for_unload_before_deleting_data`.
5. **Id and version rules differ between mibpkg and the runtime.** A 41-character id, or a `"1.0"` version, must fail the Gradle build with a message naming the rule, not on the unit. **Test:** Task 12, `PluginJsonTest.rejectsIdAndVersionMibpkgCannotPackage`.

---

## File Structure

```
mibpkg/  (branch feat/mibsdk-packages)
├── src/rwhold.rs            NEW  cross-process /mnt/app|/mnt/system rw holders + remount lock
├── src/ctx.rs               MOD  Mounts delegates first-acquire/last-release to rwhold; Ctx.approved
├── src/lock.rs              MOD  Lock::acquire_at(path, wait) shared by the action lock and rwlock
├── src/backups.rs           MOD  absence tombstones (<encoded>.absent), forget()
├── src/manifest.rs          MOD  [plugin] / [sdk] tables, kind(), validation, RESERVED += rw, rwlock, mibsdk
├── src/index.rs             MOD  IndexEntry.kind, IndexEntry.permissions
├── src/unit.rs              MOD  hmi_version(layout)
├── src/mibsdk/mod.rs        NEW  SDK paths, installed_sdk(), sdk_running(), reboot flag
├── src/mibsdk/stamp.rs      NEW  D1 stamp parse + verify
├── src/mibsdk/catalog.rs    NEW  permissions.json (plain words, loud flag)
├── src/mibsdk/grants.rs     NEW  grants.properties read / set line / drop line; approval arithmetic
├── src/mibsdk/bundles.rs    NEW  port of gen_bundles.py generate + sdk.properties render
├── src/mibsdk/ack.rs        NEW  wait for the runtime's reload confirmation in /tmp/mibsdk.log
├── src/mibsdk/ledger.rs     NEW  read ledger.properties entries (plugin field), for refusals/warnings
├── src/mibsdk/sdk_pkg.rs    NEW  [sdk] checks, deploy (all-or-undo), undeploy
├── src/mibsdk/plugin_pkg.rs NEW  [plugin] checks, approval, deploy, activate, undeploy
├── src/install.rs           MOD  kind hooks in install_package / remove / rollback / update_all
├── src/supervisor.rs        MOD  seed_once skips packages needing approval (via install error type)
├── src/cli.rs               MOD  --approve=, --force, top-level install-file, `sdk` verbs, exit code 3
├── src/web/{api,actions,routes,upload}.rs, src/catalog.rs  MOD  permissions in views; approve in actions
├── web/js/views/{package,upload}.js, web/js/permissions.js NEW, web/tests/permissions.test.mjs NEW
├── tests/{rwhold,backups_absent,manifest_kinds,mibsdk_formats,mibsdk_bundles,sdk_pkg,plugin_pkg,cli_sdk}.rs NEW
├── tests/fixtures/mibsdk/    NEW  bundles_dsitracer_enabled.properties (copied from the SDK repo), stamps, catalog
└── Cargo.toml, packaging/mibpkg/manifest.toml.in, README.md, docs/unit-facts.md  MOD (0.8.0, docs)

mib2q-plugin-sdk/  (branch feat/mibpkg-distribution)
├── api/permissions.json                         NEW  permission catalog (single source of the plain words)
├── api/src/test/java/mibsdk/api/PermissionCatalogTest.java  NEW
├── build-logic/src/main/java/mibsdk/build/PluginJson.java   MOD  version, mibpkg-compatible id/version checks
├── build-logic/src/main/java/mibsdk/build/MpkStage.java     NEW  renders manifest.toml for [plugin] / [sdk]
├── build-logic/src/main/java/mibsdk/build/MibpkgBuildTask.java NEW  runs host `mibpkg build`
├── build-logic/src/main/java/mibsdk/build/MibPluginPlugin.java MOD  mibPackage task
├── build-logic/src/main/java/mibsdk/build/Toolchain.java    MOD  mibsdk.mibpkg property, SDK_VERSION
├── build-logic/src/test/java/mibsdk/build/{PluginJsonTest,MpkStageTest}.java NEW
├── dist/build.gradle.kts                         MOD  mibpkgPackage → build/dist/mibsdk-<version>.mpk
├── gradle.properties                             MOD  mibsdk.version=0.1.0
├── plugins/{hello,crashy}/src/main/resources/plugin.json  MOD  version 1.0.0
├── installer/mibsdk-dev                          MOD  delegate to mibpkg; keep log/status/restart
├── installer/test_mibsdk_dev.py                  MOD  transport tests against a fake on-unit mibpkg
├── installer/gen_bundles.py, installer/test_gen_bundles.py  DELETE (ported in Task 6)
└── docs/…  spec §5.3/§10 notes, car-readiness follow-ups, bench-e2e Plan 3 section
```

---

### Task 1: Cross-process read-write mount holders (fixes F8)

**Files:**
- Create: `src/rwhold.rs`, `tests/rwhold.rs`
- Modify:
  - `src/lock.rs`: extract `Lock::acquire_at(path: &Path, sys: &dyn Sys, wait: Duration) -> Result<Lock>`; `acquire` calls it with `layout.run("lock")` and `Duration::ZERO`.
  - `src/ctx.rs:10-48`: `Mounts` holds `run_dir: PathBuf` and `holder: u32`.
  - `src/manifest.rs:11`: `RESERVED` gains `"rw"`, `"rwlock"`, `"mibsdk"`.
  - `src/lib.rs`

**Interfaces:**
- Produces:
  - `pub fn rwhold::acquire(layout: &Layout, sys: &dyn Sys, mount: &str, holder: u32) -> Result<()>`. Under the remount lock it creates holder file `layout.run(&format!("rw.{tag}.{holder}"))`, then always runs `sys.remount(mount, true)` (re-asserts rw even if an outside tool flipped it).
  - `pub fn rwhold::release(layout: &Layout, sys: &dyn Sys, mount: &str, holder: u32) -> Result<()>`. Under the lock it removes its own holder file and every holder whose pid is dead. It calls `sys.remount(mount, false)` only if no live holder of that tag remains.
  - `pub fn rwhold::tag(mount: &str) -> Result<&'static str>`: `"/mnt/app"` gives `"app"`, `"/mnt/system"` gives `"system"`, anything else is an error.
  - `pub const RWLOCK_WAIT: Duration = Duration::from_secs(3)`.
  - `Mounts::new(sys, layout)`, plus test-only `Mounts::with_holder(sys, layout, holder: u32)`. `Ctx::new` passes `&layout` and `std::process::id()`. The in-process refcount is kept: `rwhold::acquire` runs only on 0→1 and `release` only on 1→0.

- [ ] **Step 1: Write the failing tests** in `tests/rwhold.rs`, using `common::fixture()` and `RecordingSys`:
```rust
#[test] fn other_live_holder_keeps_app_rw() {
    // holder A = std::process::id() (alive), holder B = this pid too but a distinct Mounts::with_holder id that is alive:
    // use a spawned `sleep 30` child's pid as B. A acquires, B acquires, A releases:
    assert_eq!(*sys.calls.borrow(), vec!["rw /mnt/app", "rw /mnt/app"]);   // no "ro"
    // B releases:
    assert_eq!(sys.calls.borrow().last().unwrap(), "ro /mnt/app");
}
#[test] fn dead_holder_is_ignored_and_swept() {
    // write tmp/mibpkg.rw.app.<pid of a reaped child>; acquire+release with a live holder → ends "ro /mnt/app",
    // and the dead holder file is gone.
}
#[test] fn nested_guards_in_one_process_touch_the_registry_once() {
    let a = ctx.mounts.rw("/mnt/app").unwrap(); let b = ctx.mounts.rw("/mnt/app").unwrap(); drop(b);
    assert!(holder_file_exists()); drop(a); assert!(!holder_file_exists());
}
#[test] fn remount_lock_held_by_live_pid_times_out_with_busy() { /* create tmp/mibpkg.rwlock with a live child pid → acquire errs "busy" within RWLOCK_WAIT + 1 s */ }
#[test] fn stale_remount_lock_is_taken_over() { /* rwlock with a dead pid → acquire succeeds */ }
#[test] fn unknown_mount_is_rejected() { assert!(rwhold::tag("/mnt/persist").is_err()); }
```
- [ ] **Step 2: Run them and confirm they fail.** Run `cargo test --test rwhold`. Expected: compile error, because `rwhold` doesn't exist yet.
- [ ] **Step 3: Implement `rwhold` and refactor `Lock` and `Mounts`.** The remount lock is `layout.run("rwlock")`, taken with `Lock::acquire_at(.., RWLOCK_WAIT)`, polling every 50 ms. A pid is alive per `sys.process_alive`. `Drop for RwGuard` logs a release error with `crate::warn!` and never panics.
- [ ] **Step 4: Run the whole suite.** Run `cargo test`. Expected: all pass. Existing assertions such as `vec!["rw /mnt/app", "ro /mnt/app"]` in `tests/install.rs` stay true, because there is a single holder.
- [ ] **Step 5: Commit.** `git add -A src tests && git commit -m "feat: refcount /mnt/app read-write across processes (holder files + remount lock)"`

### Task 2: Backups record absent files and can be forgotten

**Files:**
- Modify: `src/backups.rs`
- Test: `tests/backups_absent.rs`

**Interfaces:**
- Produces:
  - `backup_once(layout, unit_abs)` on a **missing** source writes the tombstone `backup_path(..) + ".absent"`, an empty file, instead of failing.
  - `restore(layout, unit_abs)` with a tombstone deletes the target, treating "already gone" as success.
  - `pub fn forget(layout: &Layout, unit_abs: &str) -> Result<()>` removes the backup and/or the tombstone.
  - `pub fn has_backup(layout: &Layout, unit_abs: &str) -> bool` is true for either form.
  - `restore_all` handles tombstones; it decodes the name without the `.absent` suffix.

- [ ] **Step 1: Write the failing tests.** `missing_file_backs_up_as_tombstone_and_restore_deletes_it`, `existing_backup_is_never_overwritten` (call `backup_once` twice with a changed file in between, and the first content is restored), `forget_removes_both_forms`, `restore_all_handles_tombstones`, `mode_is_preserved` (0o777 source restores 0o777).
- [ ] **Step 2: Run them and confirm they fail.** Run `cargo test --test backups_absent`. Expected: FAIL; a missing source errors today.
- [ ] **Step 3: Implement it in `src/backups.rs`.**
- [ ] **Step 4: Run the tests and confirm they pass.** Run `cargo test --test backups_absent && cargo test --test hardening`. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(backups): tombstones for files absent before an edit; forget()`

### Task 3: Manifest kinds `[plugin]` and `[sdk]`, and index fields

**Files:**
- Modify: `src/manifest.rs`, `src/index.rs:33-45` and `index_dir` (`:169-205`)
- Test: `tests/manifest_kinds.rs`, `tests/index.rs`

**Interfaces:**
- Produces, in `src/manifest.rs`:
  ```rust
  pub struct PluginSpec { pub jar: String, pub api: u32, pub permissions: Vec<String> }      // [plugin]
  pub struct SdkSpec { pub api: u32, pub hmi: Vec<String>, pub jars: Vec<String>, pub configurator: String,
                       pub bundle: String, pub tracer_configurator: String,
                       #[serde(default)] pub properties: Vec<String>, pub permissions: String }   // [sdk]
  pub enum Kind { Plain, Plugin, Sdk }
  Manifest { …, #[serde(default)] pub plugin: Option<PluginSpec>, #[serde(default)] pub sdk: Option<SdkSpec> }
  impl Manifest { pub fn kind(&self) -> Kind }
  ```
- Validation (all `bail!` with the quoted words):
  - Both tables set: "a package is either [plugin] or [sdk]".
  - `[plugin]` or `[sdk]` together with `[[file]]`, `[[service]]`, `[[config]]` or `[scripts]`: "plugin and sdk packages are declarative: no files, services, configs or scripts".
  - `[plugin]` with a name shorter than 2: "plugin id … must be 2-40 characters".
  - `[sdk]` with `[package].name != "mibsdk"`: "the SDK package must be named mibsdk".
  - Each jar, plus the stamp named `<jar>.stamp`, and `permissions` must be a single path component that passes `safe_rel` and ends `.jar` (`.json` for `permissions`).
  - Each permission matches `[A-Za-z0-9._-]+`.
  - `bundle` must contain `=`.
- `IndexEntry` gains:
  - `#[serde(default, skip_serializing_if = "Option::is_none")] pub kind: Option<String>`, set to `"plugin"` or `"sdk"`;
  - `#[serde(default, skip_serializing_if = "Vec::is_empty")] pub permissions: Vec<String>`.

  `index_dir` fills both from the manifest. `Compat` is unchanged: it is `deny_unknown_fields` and older cars parse it.

- [ ] **Step 1: Write the failing tests.**
  - `plugin_manifest_parses_and_is_kind_plugin`, using:
    ```toml
    [package]
    name="hello"
    version="1.0.0"

    [plugin]
    jar="hello.jar"
    api=1
    permissions=["vehicle.read"]
    ```
  - `sdk_manifest_parses`, with every `SdkSpec` field.
  - One test per refusal above, asserting the message substring.
  - `index_entry_carries_kind_and_permissions_and_omits_them_for_plain_packages`: the serialized JSON of a plain package has no `kind` and no `permissions` key.
- [ ] **Step 2: Run them and confirm they fail.** Run `cargo test --test manifest_kinds --test index`. Expected: FAIL with "unknown field `plugin`".
- [ ] **Step 3: Implement the kinds and the index fields.**
- [ ] **Step 4: Run the whole suite.** Run `cargo test`. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(manifest): declarative [plugin] and [sdk] package kinds; kind/permissions in index.json`

### Task 4: SDK facts — paths, HMI version, M7 stamp

**Files:**
- Create: `src/mibsdk/mod.rs`, `src/mibsdk/stamp.rs`
- Modify: `src/unit.rs`, `src/lib.rs`
- Test: `tests/mibsdk_formats.rs`, `tests/fixtures/mibsdk/`

**Interfaces:**
- Produces, in `src/mibsdk/mod.rs`, these constants:
  - `BASE = "/mnt/app/eso/hmi/lsd/mibsdk"`, `PERSIST = "/mnt/persist/mibsdk"`, `RAM = "/ramdisk/mibsdk"`;
  - `LSD = "/mnt/app/eso/hmi/lsd"`, `JARS = "/mnt/app/eso/hmi/lsd/jars"`;
  - `BUNDLES = "/mnt/app/eso/hmi/lsd/bundles_dsitracer_enabled.properties"`;
  - `MARKER = "/mnt/app/eso/hmi/lsd/dsitracer_activated"`, `TRACER_JAR = "/mnt/app/eso/hmi/lsd/DSITracer.jar"`;
  - `LOG = "/tmp/mibsdk.log"`, `HMI_ROOT = "/mnt/app/eso/hmi"`, `SDK_PKG = "mibsdk"`.

  And these functions:
  - `pub fn installed_sdk(layout: &Layout) -> Option<(String /*version*/, SdkSpec, PathBuf /*ver dir*/)>`: read through `install::current_manifest`.
  - `pub fn reboot_flag(layout) -> PathBuf` = `layout.run("sdk-reboot")`. It is RAM, so a power cycle clears it.
  - `pub fn sdk_running(layout) -> bool`: true when `/tmp/mibsdk.log` exists and the reboot flag does not.
- `pub fn unit::hmi_version(layout: &Layout) -> Option<String>`: the first `HMI` line of `/ifs/version_info.txt`, value after `=`, all whitespace removed.
- `src/mibsdk/stamp.rs` provides:
  - `pub struct Stamp { pub artifact: String, pub sha256: String, pub kind: String, pub checks: Vec<String>, pub api: Option<u32>, pub id: Option<String>, pub unrestricted: bool }`;
  - `pub fn parse(text: &str) -> Result<Stamp>`, which requires `format=1`;
  - `pub fn verify(jar: &Path, stamp_file: &Path, kind: &str) -> Result<Stamp>`. It errors when:
    - the stamp is missing: "<jar> has no stamp: only jars built by the SDK's checked tasks (mibPluginJar / :dist:assembleUnit) can be installed (M7)";
    - the artifact name doesn't match;
    - `sha256_file(jar)` doesn't match;
    - the kind doesn't match;
    - a required check is missing. The required sets are plugin `classVersion48,linkCheck,permissionScan` and sdk `classVersion48,distLinkCheck`.

- [ ] **Step 1: Write the failing tests.**
  - `hmi_version_reads_the_hmi_line`. Fixture `version_info.txt` lines: `MU = …` and `HMI = HMI_AU_MIB2_HQC_NAR_G22_C10_180803 `.
  - `hmi_version_none_without_file`.
  - `stamp_verify_accepts_matching_plugin_stamp`.
  - `stamp_verify_rejects_missing_stamp_with_m7_message`.
  - `stamp_verify_rejects_sha_mismatch_after_jar_edit`.
  - `stamp_verify_rejects_missing_check` (`checks=classVersion48,linkCheck`).
  - `stamp_ignores_unknown_keys`.
  - `installed_sdk_none_until_installed`.
- [ ] **Step 2: Run them and confirm they fail.** Run `cargo test --test mibsdk_formats`. Expected: FAIL (the module is missing).
- [ ] **Step 3: Implement `mibsdk/mod.rs`, `stamp.rs` and `unit::hmi_version`.**
- [ ] **Step 4: Run the tests and confirm they pass.** Run `cargo test --test mibsdk_formats`. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(mibsdk): SDK paths, HMI version, M7 build-stamp verification`

### Task 5: Permission catalog, grants file, approval arithmetic, ledger reader

**Files:**
- Create: `src/mibsdk/catalog.rs`, `src/mibsdk/grants.rs`, `src/mibsdk/ledger.rs`
- Test: `tests/mibsdk_formats.rs` (extend)

**Interfaces:**
- **Catalog**:
  - `pub struct PermInfo { pub id: String, pub group: String, pub text: String, pub loud: bool }`;
  - `pub fn catalog::parse(json: &str) -> Result<Vec<PermInfo>>`, accepting `{"format":1,"api":N,"permissions":[…]}`;
  - `pub fn catalog::installed(layout) -> Option<Vec<PermInfo>>`, which reads `BASE/permissions.json`;
  - `pub fn catalog::describe(cat: Option<&[PermInfo]>, ids: &[String]) -> Vec<String>`: each line is `"<text> (<id>)"`, prefixed `"WARNING: "` when `loud`, or `"<id> (unknown to the installed SDK)"`.
- **Grants**:
  - `pub fn grants::read(layout) -> BTreeMap<String, BTreeSet<String>>`;
  - `pub fn grants::set(layout, id: &str, perms: &BTreeSet<String>) -> Result<()>` rewrites only that line (port of `mibsdk-dev`'s embedded Python; comment lines kept), writing atomically with `fsutil::atomic_write`. The caller holds rw;
  - `pub fn grants::drop_line(layout, id) -> Result<()>`.
- **Approval**:
  - `pub struct Approval { pub ask: Vec<String>, pub grant: BTreeSet<String> }`;
  - `pub fn grants::plan(declared_new: &[String], declared_old: Option<&[String]>, current: &BTreeSet<String>) -> Approval`. Here `ask = declared_new − declared_old` (all of `declared_new` when `declared_old` is None), and `grant = (current ∩ declared_new) ∪ ask`.
- **Ledger**:
  - `pub struct LedgerEntry { pub key: String, pub plugin: String, pub line: String }`;
  - `pub fn ledger::read(layout) -> Result<Vec<LedgerEntry>>` reads `PERSIST/ledger.properties`; format `key=plugin|scope|prior|active`, blank lines skipped, missing file = empty;
  - an unreadable existing file is an `Err`, so callers fail closed.

- [ ] **Step 1: Write the failing tests.**
  - `grants_set_rewrites_one_line_and_keeps_others_and_comments`.
  - `grants_set_appends_when_absent`.
  - `grants_drop_line`.
  - `plan_fresh_install_asks_everything`.
  - `plan_update_asks_only_new_and_keeps_revocation`: old `[a,b]`, new `[a,b,c]`, current `{b}` gives `ask=[c]` and `grant={b,c}`.
  - `plan_dropped_permission_is_removed_from_grant`.
  - `catalog_describe_marks_loud_and_unknown`.
  - `ledger_read_parses_plugin_field`.
  - `ledger_read_unreadable_is_err`, using a directory in place of the file.
- [ ] **Step 2: Run them and confirm they fail.** Run `cargo test --test mibsdk_formats`. Expected: FAIL.
- [ ] **Step 3: Implement the catalog, grants and ledger modules.**
- [ ] **Step 4: Run the tests and confirm they pass.** Run `cargo test --test mibsdk_formats`. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(mibsdk): permission catalog, grants.properties editing, approval plan, ledger reader`

### Task 6: Port the bundle-list generator to Rust

**Files:**
- Create: `src/mibsdk/bundles.rs`, `tests/mibsdk_bundles.rs`
- Create: `tests/fixtures/mibsdk/bundles_dsitracer_enabled.properties` (copy of the SDK repo's `installer/testdata/bundles_dsitracer_enabled.properties`)

**Interfaces:**
- Produces:
  - `pub fn generate(stock: &[u8], sdk: &SdkSpec, tracer_active: bool) -> Result<Vec<u8>>`. Byte-for-byte semantics of `installer/gen_bundles.py::generate`, with the configurator value, bundle line and bundle name (the part of `sdk.bundle` before `=`) taken from `SdkSpec`. The tracer bundle name stays `DSITracer`.
  - `pub fn sdk_properties(sdk: &SdkSpec, tracer_active: bool) -> Vec<u8>`: `chain.configurator=<tracer_configurator>\n` when `tracer_active`, then each `sdk.properties` line plus `\n`.

- [ ] **Step 1: Port the tests.** Port every case in `installer/test_gen_bundles.py` to `tests/mibsdk_bundles.rs`, keeping the names. They cover:
  - the tracer on/off Autostart heads;
  - a commented configurator line;
  - more than one active configurator line (refused);
  - zero or several Autostart lines (refused);
  - CRLF kept;
  - a missing final EOL;
  - output being a fixed point (`generate(generate(x)) == generate(x)`);
  - every other byte unchanged.

  Also add `sdk_properties_with_and_without_tracer`, with exact bytes:
  ```
  chain.configurator=de.audi.tghu.dsitrace.TraceConfigurator
  hook.services=org.dsi.ifc.carparkingsystem.DSICarParkingSystem
  ```
- [ ] **Step 2: Run them and confirm they fail.** Run `cargo test --test mibsdk_bundles`. Expected: FAIL.
- [ ] **Step 3: Implement `bundles.rs`.** Use a hand-written line scanner; the crate has no regex.
- [ ] **Step 4: Run the tests and confirm they pass.** Run `cargo test --test mibsdk_bundles`. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(mibsdk): bundles_dsitracer_enabled.properties generator (port of gen_bundles.py)`

### Task 7: The SDK runtime package (`[sdk]`): install, update, rollback, remove

**Files:**
- Create: `src/mibsdk/sdk_pkg.rs`, `tests/sdk_pkg.rs`
- Modify: `src/install.rs`, at `install_package` (`:232-349`; call the checks before `let _rw`, and deploy inside the `staged` closure after `extract_to`/rename), `remove` (`:433-469`) and `rollback` (`:471-497`)
- Modify: `src/ctx.rs`, adding `pub force: bool`
- Modify: `src/cli.rs`, `uninstall-self`

**Interfaces:**
- Consumes:
  - `SdkSpec` and `Kind` (Task 3);
  - `stamp::verify`, `installed_sdk`, `hmi_version`, `reboot_flag` and the path constants (Task 4);
  - `ledger::read` (Task 5);
  - `bundles::generate` and `sdk_properties` (Task 6);
  - `backups::{backup_once, restore, forget, has_backup}` (Task 2).
- Produces:
  - `pub fn sdk_pkg::check(ctx: &Ctx, m: &Manifest, pkg_dir: Option<&Path>) -> Result<()>`. It does no writes and refuses, each message ending "; nothing changed", when:
    - the HMI isn't in `sdk.hmi` ("unsupported HMI '<v>' (supported: …)");
    - any `lsd/jars` entry contains `carplay` (case-insensitive), or any `*.carplay-stock` file exists under `HMI_ROOT` (recursive walk with `fs::read_dir`) — "carplay-rgi is installed (…); the SDK does not support it until it migrates (spec §13)";
    - `TRACER_JAR` is missing;
    - an old-layout leftover exists (`BASE/data`, `BASE/ledger.properties`, `BASE/cache` or `BASE/boot.state`): "files from an older mibsdk-dev install are in <path>; move them to /mnt/persist/mibsdk by hand";
    - this is an update or rollback and an installed plugin package's `[plugin].api != sdk.api` ("<id> needs SDK api N").
  - `pub fn sdk_pkg::deploy(ctx: &Ctx, m: &Manifest, ver_dir: &Path, prev_dir: Option<&Path>) -> Result<()>`. The caller holds rw. It is **all-or-undo**:
    - on error, it re-deploys from `prev_dir` when given;
    - for a fresh install, it restores `BUNDLES` and `MARKER` from the backups and deletes the jars it wrote;
    - it then returns the original error.

    Order:
    1. Verify every jar's stamp with kind `sdk`.
    2. Adopt or back up (below).
    3. Create `BASE/plugins` and `PERSIST`.
    4. Copy the jars with `copy_atomic(…, 0o644)`.
    5. Write `BASE/sdk.properties`.
    6. Copy `permissions.json` to `BASE/permissions.json`.
    7. Create `MARKER` if absent.
    8. Write `BUNDLES` last, generated from the **backup** (never from the live file), with the backup's mode.
    9. Write the reboot flag.
  - **Adoption** (first mibpkg install on a unit set up by `mibsdk-dev`). If `!has_backup(BUNDLES)` and `BASE/stock/tracer.was` exists:
    - copy `BASE/stock/bundles_dsitracer_enabled.properties` into `backup_path(BUNDLES)`, with the mode from `BASE/stock/bundles.mode` (777 if that file is absent);
    - record `MARKER` as a tombstone when `tracer.was` is `false`, or back up the live marker when it is `true`;
    - after a byte comparison, remove `BASE/stock`.

    Otherwise, if the live `BUNDLES` contains `mibsdk` and there is no backup, refuse ("already references mibsdk but no stock backup exists; restore the stock file by hand"). Otherwise run `backup_once(BUNDLES)` and `backup_once(MARKER)`. The tracer is active when the `MARKER` backup isn't a tombstone.
  - `pub fn sdk_pkg::undeploy(ctx: &Ctx, purge: bool) -> Result<String>`. The caller holds rw. It refuses when:
    - any plugin package is installed ("remove these plugins first: …");
    - `ledger::read` has entries and `!ctx.force` (message lists them, as `mibsdk-dev`'s `check_ledger` did, and ends "add --force to uninstall anyway").

    Then it:
    1. restores `BUNDLES` (first) and `MARKER` (a tombstone deletes it);
    2. removes the three jars, `BASE/sdk.properties`, `BASE/permissions.json` and `BASE/grants.properties`, plus `PERSIST` and `RAM` when `purge`;
    3. removes `BASE` itself;
    4. forgets both backups.

    It returns the note "kept plugin data in /mnt/persist/mibsdk" or "removed …", and writes the reboot flag.
- `install_package`, `remove` and `rollback` dispatch on `manifest.kind()`. For `Kind::Sdk`, the result message gets "; reboot the unit (or restart the HMI) to start it" (or "to finish removing it"). `remove` gains a `force` read from `ctx.force`.
- `uninstall-self` refuses while `mibsdk` or any plugin package is installed.

- [ ] **Step 1: Write the failing tests.** Use a fixture helper `sdk_unit(f)` that creates `lsd/` with the stock bundles fixture (mode 0o777), `dsitracer_activated`, `DSITracer.jar` and `jars/`, plus `ifs/version_info.txt`. Use a helper `sdk_src(dir, version)` that writes the `[sdk]` manifest, three jars and their valid stamps, and `permissions.json`.
  - `sdk_fresh_install_writes_jars_props_bundles_last_and_flags_reboot`:
    - the jar bytes are equal to the sources;
    - `BUNDLES` equals `bundles::generate(stock, spec, true)`, and its mode is 0o777;
    - `sdk.properties` begins `chain.configurator=`;
    - the reboot flag exists;
    - the message ends "reboot the unit (or restart the HMI) to start it".
  - `sdk_install_on_tracer_off_unit_creates_marker_and_restore_removes_it`.
  - `sdk_install_adopts_mibsdk_dev_stock_backup` (Review Focus 3). The live `BUNDLES` already holds generated content, and `BASE/stock/{bundles_dsitracer_enabled.properties,bundles.mode,tracer.was}` hold stock, `777` and `true`. After install, `backups::backup_path(BUNDLES)` content equals the stock fixture, and `BASE/stock` is gone.
  - `sdk_install_refuses_generated_bundles_without_backup`.
  - `sdk_install_refuses_unsupported_hmi_carplay_jar_carplay_stock_file_missing_tracer_and_old_layout`: one assert per case; nothing changed (the tree is compared before and after).
  - `sdk_install_refuses_unstamped_jar`.
  - `sdk_deploy_failure_undoes_fresh_install`: make `jars/mibsdk-adapter-hqc.jar` a directory so the copy fails. Afterwards, `BUNDLES` equals stock, no SDK jar is left, and `current` isn't written.
  - `sdk_update_failure_redeploys_previous_version`.
  - `sdk_update_refused_while_plugin_needs_other_api`.
  - `sdk_remove_refuses_with_plugins_installed`.
  - `sdk_remove_refuses_on_ledger_entries_unless_force`.
  - `sdk_remove_restores_stock_bundles_byte_for_byte_and_mode_keeps_persist`.
  - `sdk_remove_purge_removes_persist_and_ram`.
  - `uninstall_self_refuses_while_mibsdk_installed`.
- [ ] **Step 2: Run them and confirm they fail.** Run `cargo test --test sdk_pkg`. Expected: FAIL.
- [ ] **Step 3: Implement `sdk_pkg.rs` and the `install.rs` dispatch.** Checks run before `let _rw`; deploy runs inside `staged`, so a failure removes the staged version dir as today.
- [ ] **Step 4: Run the whole suite.** Run `cargo test`. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(mibsdk): SDK runtime package: stamped jars, bundle list from backup, all-or-undo, guarded uninstall`

### Task 8: Plugin packages (`[plugin]`): checks, approval, deploy, hot-load, remove

**Files:**
- Create: `src/mibsdk/plugin_pkg.rs`, `src/mibsdk/ack.rs`, `tests/plugin_pkg.rs`
- Modify:
  - `src/install.rs`: the same dispatch points as Task 7, plus `update_all`, which turns `NeedsApproval` into "<name>: needs permission approval (…)";
  - `src/ctx.rs`: `pub approved: Option<Vec<String>>`;
  - `src/supervisor.rs:652-678`: `seed_once` logs and skips a `NeedsApproval` result.

**Interfaces:**
- Produces, in `src/mibsdk/plugin_pkg.rs`:
  - `#[derive(Debug)] pub struct NeedsApproval { pub package: String, pub version: String, pub ask: Vec<String>, pub lines: Vec<String> }`. It implements `Display` as "approve these permissions to install <pkg> <ver>: <lines joined by '; '>" and `std::error::Error`; callers use `anyhow::Error::downcast_ref::<NeedsApproval>()`.
  - `pub fn check(ctx: &Ctx, m: &Manifest, old: Option<&Manifest>) -> Result<Approval>`. It does no writes and refuses when:
    - the SDK isn't installed ("install the mibsdk package first");
    - `plugin.api != sdk.api`;
    - the unit HMI isn't in `sdk.hmi` ("the SDK does not support this firmware (…), so no plugin would load");
    - a permission is unknown to the installed catalog.

    Otherwise it computes `grants::plan`, and returns `Err(NeedsApproval)` unless `ask ⊆ ctx.approved.unwrap_or_default()`.
  - `pub fn deploy(ctx: &Ctx, m: &Manifest, ver_dir: &Path, approval: &Approval) -> Result<()>`. The caller holds rw. It runs `stamp::verify(kind "plugin")` and checks that the stamp `id` equals the name, copies the jar to `BASE/plugins/<id>.jar` (0o644), then calls `grants::set(id, &approval.grant)`.
  - `pub fn activate(ctx: &Ctx, id: &str) -> String` is `activate_with(ctx, id, ack::ACK_TIMEOUT)`. `pub fn activate_with(ctx: &Ctx, id: &str, timeout: Duration) -> String` runs after the commit point. It counts the `reload requested for <id>` lines in `LOG`, writes a fresh token (`<now_secs>-<pid>-<counter>`) to `BASE/plugins/<id>.reload` with `atomic_write` under rw, then calls `ack::wait`. It returns the note:
    - "loaded: <STATE> <reason>";
    - "reload requested; the SDK did not confirm within 6 s (it loads at the next HMI start)";
    - or, when `!sdk_running`, "the SDK is not running; it loads at the next boot".
  - `pub fn ack::wait(layout, id: &str, before: usize, timeout: Duration) -> Option<(String, String)>`. It polls `LOG` every 250 ms until the count of `reload requested for <id>` exceeds `before`. It then returns the state and reason of the last `STATUS <id> <STATE> <reason>` line after that point; a line that is only `DISABLED reloading` keeps it waiting. `pub const ACK_TIMEOUT: Duration = Duration::from_secs(6)`.
  - `pub fn undeploy(ctx: &Ctx, id: &str, purge: bool) -> Result<String>`. The caller holds rw. It:
    1. deletes the jar;
    2. writes a fresh marker (the runtime disables the plugin and restores its orphaned overrides);
    3. drops the grants line;
    4. waits for the ack when the SDK is running.

    Purge then removes `PERSIST/data/<id>.properties` and `.tmp` only when the ack came back or the SDK isn't running. Otherwise it keeps them and the note says "data kept: the SDK did not confirm the unload; run `mibpkg sdk data-clear <id>` after a restart". When `ledger::read` has entries for `id`, it appends "the SDK restores its N vehicle override(s) when it unloads it".
- **Rollback**: deploy the previous version's jar with `grant = current ∩ declared_prev`. It never asks, then activates.
- `install_file` and `install` both go through `install_package`, so an uploaded file and a collection entry follow the same rules.

- [ ] **Step 1: Write the failing tests.** Use helpers `with_sdk(f)` (installs Task 7's SDK fixture) and `plugin_src(dir, id, ver, perms)` (jar plus valid stamp). Fake the runtime's ack by writing `tmp/mibsdk.log` from a thread spawned before the install.
  - `plugin_install_without_approval_returns_needs_approval_and_changes_nothing`, asserting `downcast_ref::<NeedsApproval>().ask == ["vehicle.read"]`.
  - `plugin_install_with_approval_deploys_jar_writes_grants_and_marker`, with `ctx.approved = Some(vec!["vehicle.read"])`. It asserts the jar is at `BASE/plugins/hello.jar`, `grants.properties` has `hello=vehicle.read`, the `.reload` file exists, and the message contains "loaded: ENABLED".
  - `plugin_install_without_permissions_needs_no_approval`.
  - `plugin_update_asks_only_new_permissions_and_keeps_revocations` (Review Focus 2).
  - `plugin_install_refused_without_sdk`, `plugin_install_refused_on_api_mismatch`, `plugin_install_refused_on_unsupported_hmi`, `plugin_install_refused_on_permission_unknown_to_sdk`.
  - `plugin_install_refused_when_stamp_id_differs`.
  - `plugin_install_when_sdk_not_running_says_next_boot`.
  - `plugin_install_without_ack_says_not_confirmed`, through `activate_with(.., Duration::from_millis(300))`.
  - `plugin_remove_keeps_data_by_default_and_drops_grants_line`.
  - `plugin_purge_waits_for_unload_before_deleting_data` (Review Focus 4): no ack means data kept with a note; ack means data gone.
  - `plugin_rollback_never_adds_grants`.
  - `update_all_reports_needs_approval_and_updates_the_rest`.
  - `seed_skips_plugin_needing_approval`.
- [ ] **Step 2: Run them and confirm they fail.** Run `cargo test --test plugin_pkg`. Expected: FAIL.
- [ ] **Step 3: Implement `plugin_pkg.rs`, `ack.rs` and the dispatch.**
- [ ] **Step 4: Run the whole suite.** Run `cargo test`. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(mibsdk): plugin packages with permission approval, hot-load via reload marker, keep/purge data on remove`

### Task 9: CLI — approval, force, install-file, and the `sdk` verbs

**Files:**
- Modify: `src/cli.rs` (`USAGE`, arg parsing at `:63-69`, the action arm at `:160-167`, `action()` at `:346`)
- Test: `tests/cli_sdk.rs`

**Interfaces:**
- Produces:
  - **Global action flags.** `--approve=<perm,…>` (or `--approve=` for none) and `--force` are accepted after `install | install-file | update-all | remove | gem install | gem install-file | gem remove`. They set `ctx.approved` and `ctx.force`.
  - **`install-file <pkg> <upload-id> [collection|-]`** joins the top-level lock arm, so its exit code reflects failure. The default collection is `-`.
  - **Exit code 3** when the error downcasts to `NeedsApproval`. Stdout then gets one line per `lines` entry, then `approve with: --approve=<ask joined by ','>`. In the `gem` arm the result stays `FAILED: <Display>`; the web asks first (Task 10).
  - **`mibpkg sdk <verb>`**, under the action lock:
    - `status`: prints `sdk: <version|not installed>`, `api: N`, `hmi: <unit hmi> (supported|UNSUPPORTED)`, `running: yes|no`, `reboot needed: yes|no`, one `plugin <id> <version> grants=<…>` per plugin package, and `ledger: <n> override(s)` followed by the lines. Spec §6.1: when the ledger isn't empty it adds `WARNING: vehicle overrides are in force; disable their plugins before a firmware update (a firmware without a matching adapter cannot restore them)`. If the HMI is also UNSUPPORTED, it adds `WARNING: the SDK is off on this firmware and cannot restore these overrides`;
    - `grant <id> <perm,…>`: validated with `pkg_arg`; the list may be empty; it calls `grants::set` under rw, then `plugin_pkg::activate`;
    - `push <id>`: dev only. It takes the jar from `layout.run("push.<id>.jar")` and the stamp from `push.<id>.jar.stamp`, then runs `stamp::verify`. It refuses when `id` is installed as a package ("<id> is installed as a package; remove it first or install a newer version"), deploys with the existing grants, activates, and deletes both `/tmp` files whatever the outcome;
    - `safe-mode on|off`: `on` touches `BASE/SAFE_MODE` under rw. `off` removes it and writes `PERSIST/boot.state` = `unhealthyBoots=0\nlastShutdownClean=true\nwrapBoots=0\n` (no rw needed);
    - `data-clear <id>`: removes `PERSIST/data/<id>.properties{,.tmp}` and prints the restart advice from `mibsdk-dev`.

    `mibpkg sdk` without a verb prints usage and exits 2. `mibsdk-dev` uses `mibpkg sdk status`'s exit code 0 as its "mibpkg new enough" probe.

- [ ] **Step 1: Write the failing tests.** Use `--root <tmp>` against the binary, following `tests/cli_verbs.rs`.
  - `install_file_needing_approval_exits_3_and_prints_approve_hint`.
  - `sdk_status_warns_about_ledger_before_firmware_update`.
  - `install_file_with_approve_installs`.
  - `remove_mibsdk_force_flag_reaches_undeploy`.
  - `sdk_status_lists_plugins_grants_ledger`.
  - `sdk_grant_rewrites_line_and_writes_marker`.
  - `sdk_push_refuses_unstamped_and_package_owned_ids_and_cleans_tmp`.
  - `sdk_safe_mode_on_off`.
  - `sdk_data_clear`.
  - `sdk_without_verb_exits_2`.
- [ ] **Step 2: Run them and confirm they fail.** Run `cargo test --test cli_sdk`. Expected: FAIL.
- [ ] **Step 3: Implement the flags and verbs in `src/cli.rs`.**
- [ ] **Step 4: Run the whole suite.** Run `cargo test`. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(cli): --approve/--force, install-file, mibpkg sdk status|grant|push|safe-mode|data-clear`

### Task 10: Web — show permissions in plain words and approve before installing

**Files:**
- Modify:
  - `src/catalog.rs`: `Avail` and `Inst` gain `kind: Option<String>` and `permissions: Vec<String>` (`described`). The installed side comes from the current manifest, plus `granted` from `grants::read`;
  - `src/web/upload.rs:141-157`: `Inspection` gains `kind`, `permissions` and `needs_approval: Vec<String>`, the latter computed with `grants::plan` against the installed version;
  - `src/web/actions.rs:12-23`: `Install` gains `#[serde(default)] approve: Vec<String>`, validated against the permission pattern and appended as `--approve=…`;
  - `src/web/routes.rs:~375`: the upload install takes `approve` the same way;
  - `web/js/views/package.js`, `web/js/views/upload.js`.
- Create: `web/js/permissions.js`, `web/tests/permissions.test.mjs`
- Test: `tests/web_actions.rs`, `tests/web_upload.rs` (extend)

**Interfaces:**
- Produces:
  - JSON fields `permissions: [{id, text, loud, granted?}]` on package views and the upload inspection;
  - `needs_approval: [id]` on the inspection;
  - in JS, `export function approvalSheet(perms /*[{id,text,loud}]*/, onApprove /*(ids)=>void*/)`, which returns a DOM node listing each line (`loud` ones get class `warn` and the prefix "Warning:") and an "Allow and install" button that calls `onApprove(ids)`;
  - the Install/Update buttons of plugin packages open the sheet when the newly asked set isn't empty. Cancel sends nothing.

- [ ] **Step 1: Write the failing tests.**
  - `install_action_with_approve_builds_argv_with_flag`.
  - `install_action_rejects_bad_permission_strings`, e.g. `"a b"` or `"--x"`.
  - `upload_inspection_lists_needs_approval_for_new_permissions_only`.
  - JS: `approvalSheet lists plain words, marks loud, passes ids` (`node --test web/tests/permissions.test.mjs`, following `web/tests/jobs.test.mjs`).
- [ ] **Step 2: Run them and confirm they fail.** Run `cargo test --test web_actions --test web_upload && node --test web/tests/*.test.mjs`. Expected: FAIL.
- [ ] **Step 3: Implement the API fields, the action field and the JS sheet.**
- [ ] **Step 4: Run the tests and confirm they pass.** Run the same commands. Expected: PASS. Then check by eye with `scripts/web-fixture.sh <dir>` and `MIBPKG_WEB_DIR=web`: install a plugin `.mpk` from the Upload view and see the sheet.
- [ ] **Step 5: Commit.** `feat(web): plugin permissions in plain words; approve before install/update`

### Task 11: mibpkg 0.8.0 — version, docs, cross-build

**Files:**
- Modify: `Cargo.toml` (`version = "0.8.0"`), `README.md` (a "Plugin SDK packages" section), `docs/unit-facts.md` (rows for `/ifs/version_info.txt`, the SDK paths and the rw holder protocol), `docs/ideas.md` (`[[patch]]` note: SDK uses `[sdk]` instead)

**Interfaces:**
- Produces:
  - the cross-built unit binary `out/…/mibpkg` (from `scripts/build.sh`) and `mibpkg-0.8.0.mpk`, built with `mibpkg build` from `packaging/mibpkg`;
  - the documented **rw holder protocol for other tools**: "take `/tmp/mibpkg.rwlock` (`create_new`, pid inside), create `/tmp/mibpkg.rw.app.<pid>`, `mount -uw`; on release remove your file and `mount -ur` only if no live `mibpkg.rw.app.*` remains".

- [ ] **Step 1: Bump the version and write the docs.**
- [ ] **Step 2: Run the full suite.** Run `cargo test && node --test web/tests/*.test.mjs`. Expected: PASS.
- [ ] **Step 3: Cross-build.** Run `scripts/build.sh`. Expected: it exits 0 and prints the path of the armv7 binary.
- [ ] **Step 4: Commit.** `chore: bump to 0.8.0 (mibsdk plugin and SDK packages, cross-process rw holders)`

### Task 12: SDK repo — permission catalog, plugin ids and versions mibpkg can package

**Files:**
- Create: `api/permissions.json`, `api/src/test/java/mibsdk/api/PermissionCatalogTest.java` (add `testImplementation` JUnit as `runtime/build.gradle.kts` gets it through the convention, if `api` lacks it)
- Create: `build-logic/src/test/java/mibsdk/build/PluginJsonTest.java`
- Modify: `build-logic/src/main/java/mibsdk/build/PluginJson.java` (add `final String version;`), `plugins/hello/…/plugin.json`, `plugins/crashy/…/plugin.json` (`"version": "1.0.0"`)

**Interfaces:**
- Produces:
  - `api/permissions.json`, with exactly this content:
    ```json
    {"format":1,"api":1,"permissions":[
     {"id":"vehicle.read","group":"Vehicle","text":"Read vehicle state: ignition, gear, parking sensors","loud":false},
     {"id":"vehicle.parking.write","group":"Vehicle","text":"Change parking-sensor settings, for example mute the rear tones","loud":false},
     {"id":"hooks.parking","group":"Hooks","text":"Watch and override parking-system messages","loud":false},
     {"id":"platform.unrestricted","group":"Danger","text":"Unrestricted: may use reflection and stock HMI classes; the SDK cannot contain it","loud":true}]}
    ```
  - `PluginJson.read` additionally fails when:
    - the id isn't 2–40 characters (`[a-z0-9][a-z0-9-]{1,39}`): "… (mibpkg package names are at most 40 characters)";
    - the id is `mibsdk`;
    - `version` isn't semver (`\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?`): "\"version\" must be semver like 1.0.0 (mibpkg compares versions)".

- [ ] **Step 1: Write the failing tests.**
  - `PermissionCatalogTest.everyPermissionsConstantHasExactlyOneEntryAndViceVersa`: reflect over the `public static final String` fields of `mibsdk.api.Permissions` and parse the JSON with `groovy.json` or a minimal reader.
  - `PermissionCatalogTest.textIsNonEmptyAndOnlyUnrestrictedIsLoud`.
  - `PluginJsonTest.rejectsIdAndVersionMibpkgCannotPackage` (Review Focus 5): a 41-character id fails, `"1.0"` fails, `"mibsdk"` fails, and `"1.0.0"` with `hello` passes.
- [ ] **Step 2: Run them and confirm they fail.** Run `./gradlew :api:test :build-logic:test` (build-logic is an included build: `./gradlew -p build-logic test`). Expected: FAIL.
- [ ] **Step 3: Implement the catalog, the `PluginJson` checks and the version bumps.** In `PluginJson.read`, change the id rule to `[a-z0-9][a-z0-9-]{1,39}`, and add `version` and the semver check.
- [ ] **Step 4: Run the tests and confirm they pass.** Run `./gradlew -p build-logic test && ./gradlew :api:test :plugins:hello:mibPluginJar :plugins:crashy:mibPluginJar :plugins:bike-rack:mibPluginJar`. Expected: PASS and BUILD SUCCESSFUL.
- [ ] **Step 5: Commit.** `feat(build): permission catalog; plugin ids and versions that mibpkg can package`

### Task 13: SDK repo — build `.mpk` packages for plugins and the SDK

**Files:**
- Create: `build-logic/src/main/java/mibsdk/build/{MpkStage,MibpkgBuildTask}.java`, `build-logic/src/test/java/mibsdk/build/MpkStageTest.java`
- Modify:
  - `build-logic/src/main/java/mibsdk/build/MibPluginPlugin.java` (register `mibPackage`);
  - `build-logic/src/main/java/mibsdk/build/Toolchain.java` (`mibpkg(Project)` reads the property `mibsdk.mibpkg`, defaulting to `mibpkg` on PATH; also `SDK_HMI = "HMI_AU_MIB2_HQC_NAR_G22_C10_180803"`);
  - `dist/build.gradle.kts` (register `mibpkgPackage`);
  - `gradle.properties` (`mibsdk.version=0.1.0`, plus a comment for `mibsdk.mibpkg`).
- Step 13.1, only if D1 hasn't landed: create `build-logic/src/main/java/mibsdk/build/StampFiles.java` and wire it into `mibPluginJar` (`doLast`) and `assembleUnit`, with `StampFilesTest`.

**Interfaces:**
- Consumes: the D1 stamps; `PluginJson.{id,version,permissions}` (Task 12); `api/permissions.json`.
- Produces:
  - `MpkStage.pluginManifest(PluginJson p, String jarName) -> String`, which renders exactly:
    ```toml
    [package]
    name = "<id>"
    version = "<version>"
    description = "<name> (mibsdk plugin)"

    [compat]
    units = ["MHI2Q"]

    [plugin]
    jar = "<id>.jar"
    api = 1
    permissions = [<"p", …>]
    ```
  - `MpkStage.sdkManifest(String version) -> String`. It renders `[package] name="mibsdk"`, `[compat] units=["MHI2Q"]` and `[sdk]` with:
    - `api = 1`, `hmi = ["HMI_AU_MIB2_HQC_NAR_G22_C10_180803"]`;
    - `jars = ["mibsdk-runtime.jar","mibsdk-adapter-hqc.jar","mibsdk-rt-retrotranslator.jar"]`;
    - `configurator = "mibsdk.adapter.hqc.ChainingConfigurator"`;
    - `bundle = "MibSdk=mibsdk.adapter.hqc.SdkBundleActivator"`;
    - `tracer_configurator = "de.audi.tghu.dsitrace.TraceConfigurator"`;
    - `properties = ["hook.services=org.dsi.ifc.carparkingsystem.DSICarParkingSystem"]`;
    - `permissions = "permissions.json"`.

    These values move here from `gen_bundles.py`. TOML strings are escaped.
  - Task `mibPackage` (plugins): stage `build/mibpkg-stage/` = `manifest.toml` + `<id>.jar` + `<id>.jar.stamp`, then run `<mibpkg> build <stage> build/mibsdk/`. The output is `build/mibsdk/<id>-<version>.mpk`.
  - Task `:dist:mibpkgPackage`: stage the three jars, their stamps, `permissions.json` and `manifest.toml`. The output is `dist/build/dist/mibsdk-<version>.mpk`.
  - `MibpkgBuildTask` (Exec-like, cacheable, with inputs the stage dir and the binary path) fails with "host mibpkg not found: build it (cargo build --release in the mibpkg repo) and pass -Pmibsdk.mibpkg=<path>" when the binary is missing.

- [ ] **Step 1: Write the failing tests.**
  - `MpkStageTest.pluginManifestIsExactAndEscaped` (a name with `"`).
  - `MpkStageTest.sdkManifestCarriesTheValuesGenBundlesUsed`, asserting each value above.
  - `MibPluginPluginFunctionalTest.mibPackageBuildsMpk`, guarded by `assumeTrue(new File(System.getProperty("mibsdk.mibpkg", "")).canExecute())`: the `.mpk` exists, and `mibpkg verify`/`tar -tzf` lists `manifest.toml`, `hello.jar` and `hello.jar.stamp`.
- [ ] **Step 2: Run them and confirm they fail.** Run `./gradlew -p build-logic test`. Expected: FAIL.
- [ ] **Step 3: Implement `MpkStage`, `MibpkgBuildTask` and the task registration.**
- [ ] **Step 4: Run them and build both packages.** Run `./gradlew -p build-logic test -Dmibsdk.mibpkg=$HOME/complex-projects/mibpkg/target/release/mibpkg && ./gradlew -Pmibsdk.mibpkg=… :dist:mibpkgPackage :plugins:hello:mibPackage :plugins:bike-rack:mibPackage`. Expected: PASS. The `.mpk` files exist, and `mibpkg build` didn't reject the manifests.
- [ ] **Step 5: Commit.** `feat(build): mibPackage and :dist:mibpkgPackage build mibpkg .mpk files`

### Task 14: `mibsdk-dev` delegates to mibpkg; delete the duplicated install logic

**Files:**
- Modify: `installer/mibsdk-dev`, `installer/test_mibsdk_dev.py`
- Delete: `installer/gen_bundles.py`, `installer/test_gen_bundles.py`. Its cases now live in mibpkg's `tests/mibsdk_bundles.rs`; `installer/testdata/` stays only if a remaining test uses it.
- Modify docs:
  - spec §5.3: add `permissions.json` to the install row, and replace the note "`mibsdk-dev` remounts" with "mibpkg owns `/mnt/app` writes";
  - spec §10: an "implemented by Plan 3" note naming `[plugin]` and `[sdk]`;
  - `docs/car-readiness.md`: close the follow-ups "`mibsdk-dev remove <id>`" (now `mibpkg remove <id>`) and the read-only-remount item; note M7 enforcement in mibpkg.

**Interfaces:**
- Consumes: the Task 9 verbs; the Task 13 `.mpk` outputs.
- Produces the `mibsdk-dev` commands. There are no remote `mount` calls left in the script.

  | Command | What it does on the unit |
  |---------|--------------------------|
  | `install` | Copy `$DIST/mibsdk-<version>.mpk` to `/tmp/mibpkg.upload.<uid>.mpk`, then run `/tmp/mibpkg install-file mibsdk <uid> -` |
  | `uninstall [--purge] [--force]` | `/tmp/mibpkg remove mibsdk [--purge] [--force]` |
  | `install-plugin <file.mpk> [--approve=…]` | Copy, then `install-file <id> <uid> - --approve=…`. Exit code 3 is passed through with mibpkg's lines. |
  | `remove <id> [--purge]` | `/tmp/mibpkg remove <id> [--purge]` |
  | `push <plugin.jar>` | Requires `<jar>.stamp` beside the jar (D1). Copy both to `/tmp/mibpkg.push.<id>.jar{,.stamp}`, then `/tmp/mibpkg sdk push <id>`. |
  | `grant <id> <perms>` | `/tmp/mibpkg sdk grant …` |
  | `safe-mode on\|off` | `/tmp/mibpkg sdk safe-mode …` |
  | `data-clear <id>` | `/tmp/mibpkg sdk data-clear …` |
  | `status` | Prints `sdk status`, then the `STATUS` lines of the log |
  | `log`, `restart` | Unchanged |

  - `<uid>` comes from `od -An -tx1 -N6 /dev/urandom` on the Mac. It must pass mibpkg's `web::settings::is_id`; check that in the test.
  - Every command first runs `ssh … /tmp/mibpkg sdk status >/dev/null`. A non-zero exit dies with "mibpkg on <host> is missing or older than 0.8.0; update it (see the mibpkg README)".
  - The `.mpk` id for `install-plugin` comes from the archive's `manifest.toml`, read with `tar -xzOf <mpk> manifest.toml` on the Mac.

- [ ] **Step 1: Rewrite the tests.** Rewrite `installer/test_mibsdk_dev.py` around a fake `ssh` (kept), where a fake `/tmp/mibpkg` script under `$FAKE_UNIT` appends its argv to `$FAKE_UNIT/mibpkg.argv` and exits with `$FAKE_MIBPKG_RC`. Tests:
  - `install_uploads_mpk_and_calls_install_file`: the upload file exists with the same bytes, and argv is `install-file mibsdk <uid> -`.
  - `old_or_missing_mibpkg_is_refused_before_any_upload`.
  - `install_plugin_passes_approve_and_exit_3_through`.
  - `push_requires_stamp_beside_jar` (nothing is uploaded without it).
  - `push_uploads_jar_and_stamp_and_calls_sdk_push`.
  - `uninstall_flags_pass_through`.
  - `grant_safe_mode_data_clear_map_to_sdk_verbs`.
  - `script_has_no_remote_mount_calls`, asserting `"mount -u"` isn't in the script text.
- [ ] **Step 2: Run them and confirm they fail.** Run `python3 -m unittest installer/test_mibsdk_dev.py -v`. Expected: FAIL.
- [ ] **Step 3: Rewrite `mibsdk-dev`.** Keep `usage`, `die`, `info`, `SSH_OPTS`, `set_host` and the host-argument arity rule. Delete `push_file`, `migrate_*`, `LEDGER_PY`, `check_ledger`, `restore_bundles_mode`, `rw_*` and `sweep_push_tmp`. Delete `gen_bundles.py` and its test, and update the docs listed above.
- [ ] **Step 4: Run the tests and confirm they pass.** Run `python3 -m unittest installer/test_mibsdk_dev.py -v && ./gradlew check`. Expected: PASS.
- [ ] **Step 5: Commit.** `refactor(installer): mibsdk-dev delegates every unit write to mibpkg; drop gen_bundles.py (ported to mibpkg)`

### Task 15: Bench end-to-end (10.20.0.23)

**Files:**
- Modify: `docs/bench-e2e.md`. Add a "Plan 3: mibpkg distribution" section with steps, outputs, screenshots under `docs/bench-e2e/` and an F-number table for findings.

**Interfaces:**
- Consumes: mibpkg 0.8.0 (Task 11), `mibsdk-0.1.0.mpk`, and `hello`, `crashy` and `bike-rack` `.mpk` files (Task 13), plus the new `mibsdk-dev` (Task 14).

`SSH` below means `ssh -o HostKeyAlgorithms=ssh-rsa -o PubkeyAcceptedKeyTypes=ssh-rsa root@10.20.0.23`. Look at the screen with the keypanel (`curl -s 10.20.0.23:8099/status`, then poll `/screen.jpg` twice). Leave the unit on its home screen at the end.

- [ ] **Step 1: Baseline.**
  - Run `SSH /tmp/mibpkg status`, and note the mibpkg version.
  - Run `SSH ls -l /mnt/app/eso/hmi/lsd /mnt/app/eso/hmi/lsd/mibsdk /mnt/app/eso/hmi/lsd/mibsdk/stock`.
  - Save a local copy of `mibsdk/stock/bundles_dsitracer_enabled.properties`. It is the stock reference for Step 7.
  - Expected: the SDK was installed by the old `mibsdk-dev` (`stock/tracer.was` = `true`), and there are no `mibsdk/data` or `mibsdk/ledger.properties` leftovers.
- [ ] **Step 2: Update mibpkg to 0.8.0.**
  - Copy `mibpkg-0.8.0.mpk` to `/tmp/mibpkg.upload.<uid>.mpk`.
  - Run `SSH /tmp/mibpkg install-file mibpkg <uid> -`.
  - Wait for the supervisor to restart, then run `SSH /tmp/mibpkg sdk status`.
  - Expected: exit 0; `sdk: not installed` (it isn't a package yet); `running: yes`.
- [ ] **Step 3: Adopt the SDK as a package.**
  - Run `installer/mibsdk-dev install`.
  - Expected: "installed mibsdk 0.1.0; reboot the unit (or restart the HMI) to start it", and `mibsdk/stock/` is gone.
  - Check the mibpkg backup: `SSH cat '/mnt/app/root/mibpkg/state/backups/mnt%app%eso%hmi%lsd%bundles_dsitracer_enabled.properties'`. It must equal the Step 1 copy.
  - Run `mibsdk-dev restart`. After the unit comes back (2–3 minutes), run `mibsdk-dev status`. Expected: `boot healthy`, and keypanel `bridge_connected:true`.
- [ ] **Step 4: Install a plugin with approval (CLI).**
  - Run `mibsdk-dev remove hello` if it was pushed earlier, so the id becomes package-owned.
  - Run `mibsdk-dev install-plugin plugins/hello/build/mibsdk/hello-1.0.0.mpk`. Expected: exit 3 with "Read vehicle state: …".
  - Re-run it with `--approve=vehicle.read`. Expected: "loaded: ENABLED".
  - Check `grants.properties`, which must have `hello=vehicle.read`, and `mibsdk-dev status`.
- [ ] **Step 5: F8 regression.**
  - While `install-plugin bike-rack … --approve=vehicle.read,vehicle.parking.write` runs, loop `SSH /tmp/mibpkg gem-screens` five times in a second terminal.
  - Expected: the install succeeds, its message is "loaded: …", and `SSH ls /tmp | grep mibpkg.rw` is empty afterwards.
  - Run `SSH mount | grep /mnt/app`. Expected: `/mnt/app` is read-only afterwards.
- [ ] **Step 6: Web approval.**
  - Open the web UI and upload `crashy-1.0.0.mpk` (no permissions). It installs with no sheet.
  - Upload `bike-rack` 1.0.1, built with an extra permission for this test only. The sheet shows only the new permission.
  - Take a screenshot.
- [ ] **Step 7: Removal and restore.**
  - `mibsdk-dev remove crashy`: data kept.
  - `mibsdk-dev remove hello --purge`: data gone after "loaded: DISABLED plugin jar not found".
  - `mibsdk-dev uninstall`: refused while `bike-rack` is installed. Remove it, then run `mibsdk-dev uninstall` again.
  - Expected: `bundles_dsitracer_enabled.properties` is byte-equal to the Step 1 copy, with mode `-rwxrwxrwx`; `dsitracer_activated` is still present; the SDK jars are gone; `/mnt/persist/mibsdk` is kept.
  - Run `mibsdk-dev restart`. Expected: keypanel `bridge_connected:true`, and the home screen is stock.
- [ ] **Step 8: Reinstall for continued bench work.**
  - Run `mibsdk-dev install`, then `restart`.
  - Install `hello` and `bike-rack` with approval.
  - Record the final state in `docs/bench-e2e.md`.
- [ ] **Step 9: Commit and follow up.**
  - Commit in the SDK repo: `docs(e2e): Plan 3 verified on the bench (mibpkg 0.8.0 SDK and plugin packages)`.
  - File any F-findings as fixes in the owning repo before merging either branch.
