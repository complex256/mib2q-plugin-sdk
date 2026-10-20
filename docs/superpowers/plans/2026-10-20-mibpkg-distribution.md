# MHI2Q Plugin SDK — Plan 3: mibpkg distribution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Plugins and the SDK runtime install, update, roll back and uninstall through mibpkg. Permissions are approved at install time and can be revoked later, plugins hot-load into a running SDK, and `/mnt/app` stays writable for as long as any writer needs it.

**Architecture:**
- **mibpkg owns every on-unit write the SDK needs.** It gains two declarative package kinds, keyed by a manifest table:
  - `[sdk]`: the runtime jars, the generated bundle list (including the `HMICar=` activator), stock-file backup and restore, and the install guards (HMI, carplay-rgi, spike jar, owned classes, stock `HMICar=`);
  - `[plugin]`: one checked jar, a permission list, the grants line and the reload marker.
- **The SDK repo supplies data, not procedure.** Gradle builds the `.mpk` files with `mibpkg build`. They carry the jars, the M7 check stamps, the permission catalog (`permissions.json`) and the `[sdk]` values: configurator, bundle line, `HMICar=` values, owned class prefixes, supported HMI and API range.
- **`installer/mibsdk-dev` becomes a thin dev transport.** It copies a file into the unit's `/tmp` over SSH and calls a mibpkg verb. `log` and `restart` stay dev-only. Its command surface (including `boot-state set-wrap` and `status`'s `HMICar=` line) is unchanged, so the UI plan's bench steps run against either version.
- **One install implementation.** `gen_bundles.py` and the bash install/uninstall/remove/migrate logic are ported to Rust, and the Python and bash copies are then deleted.
- **Approval happens in the CLI and the web UI.** Since slim-GEM, GEM only installs mibpkg itself, so it has no package install for which to ask approval (spec §7: "whatever mibpkg UI did the install"). Revocation is a web control and `mibpkg sdk grant` (spec G5).
- **`/mnt/app` read-write is refcounted across processes**, through holder files in `/tmp` and a short remount lock. A mibpkg process goes read-only only when no live holder remains. Every write to `/mnt/app` also re-asserts read-write, is verified by read-back, and is retried once (mibsdk-dev's F8 discipline), because a tool outside the protocol can still remount read-only. This fixes bench-e2e F8 at its root.

**Tech Stack:**
- mibpkg: Rust 2021, crate deps unchanged (anyhow, serde/serde_json, toml, sha2, semver, flate2; tempfile for tests). Cross-built for QNX 6.5 armv7 with `scripts/build.sh` (Docker). Web front-end is plain JS with `node --test`.
- SDK: Gradle 8.13 build-logic (Java 17), JUnit 5.10.2, Python 3 (host-side `mibsdk-dev` tests only), bash.

**Spec:**
- `docs/superpowers/specs/2026-10-18-mib2q-plugin-sdk-design.md`: §10 (G8), §7 and G5 (permissions, revoke), §5.3 (packaging and paths), §6.1 (ledger), §13 (carplay-rgi guard, sole-owner rule).
- mibpkg's own design: `~/complex-projects/mibpkg/docs/superpowers/specs/2026-09-26-mibpkg-design.md`.
- Context: `docs/bench-e2e.md` (F8), `docs/car-readiness.md` (M7, I2, M2), `docs/superpowers/plans/2026-10-20-mibsdk-ui.md` (API 2, slots, `HMICar=`, owned classes, `plugins.properties`), and the Global Constraints of `docs/superpowers/plans/2026-10-18-mibsdk-foundation.md`, all of which apply here.
- Preflight that this revision applies: the car-readiness worktree's `.superpowers/sdd/mibpkg-preflight.md` (findings A1–A15, B, C1–C10, U1–U8).

**Repos, branches, order:**
- **Plan order:** car-readiness (done, merged into `feat/ui`) → UI (host tasks done on `feat/ui`) → this plan. This plan builds on `feat/ui`'s code; every name below is from `feat/ui`.
- **mibpkg** (`~/complex-projects/mibpkg`, `main` at `ee246bc`): create a worktree on a new branch `feat/mibsdk-packages` (superpowers:using-git-worktrees). Tasks 1–11 commit there. mibpkg's repo-local git identity is complex256's GitHub noreply address; keep it.
- **mib2q-plugin-sdk**: Tasks 12–16 commit on `feat/ui` (or on a branch `feat/mibpkg-distribution` cut from `feat/ui`'s tip if `feat/ui` is frozen for review). Author `complex256 <me@complex256.dev>`.
- **Lanes:**
  - Lane A: Tasks 1–11 (mibpkg), in order.
  - Lane B: Task 12 and Task 13 Steps 1–3 (SDK), in parallel with lane A.
  - Join: Task 13 Step 4 needs a host mibpkg with Tasks 3 and 4 (it runs `mibpkg build` on `[plugin]`/`[sdk]` manifests). Task 14 needs Task 9 and Task 13.
  - Tasks 15–16 (bench) need everything.
- **Host-only vs bench** (the bench is off while this plan is written):

  | Task | Where |
  |------|-------|
  | 1–14 | **Host-only**: cargo/gradle/python tests, `scripts/build.sh` (Docker), `scripts/web-fixture.sh`. Runnable now. |
  | 15 | **Bench (deploy)**: mibpkg 0.8.0 onto 10.20.0.23, adopt the old `mibsdk-dev` SDK install as a package. |
  | 16 | **Bench (E2E)**: plugin approval, F8 regression, web approval/revoke, remove, uninstall/restore, reinstall. |
- **Merging:** merge mibpkg first, as 0.8.0; then the SDK branch (its `mibsdk-dev` requires mibpkg ≥ 0.8.0 on the unit). Don't publish mibpkg 0.8.0 to the official online collection until Task 16 passes on the bench.

**Dependency D1: the M7 check stamp, as `feat/ui` writes it** (`build-logic/src/main/java/mibsdk/build/CheckStamp.java`). mibpkg consumes it unchanged:
- ISO-8859-1, `\n` line ends, one `key=value` per line, in this order: `format=1`; `kind=plugin` or `kind=unit`; `checks=<comma list>`; attributes sorted by key (plugin only: `plugin.id=<id>`, `plugin.unrestricted=true|false`); then one `sha256.<jar file name>=<64 lowercase hex>` per jar, sorted by name.
- Plugin stamp: `build/mibsdk/<id>.jar.stamp` beside `<id>.jar` (written by `mibPluginJar`, checks `classVersion48,linkCheck,permissionScan`).
- SDK stamp: **one** `dist/build/dist/mibsdk-unit.stamp` (`kind=unit`, checks `classVersion48,distLinkCheck`) covering the three jars.
- `stamp::verify` (Task 4) is a port of `mibsdk-dev`'s `STAMP_PY` with the same rules and the same short reasons; M7's "only checked jars install" is enforced on the unit by mibpkg.

## Global Constraints

Everything in the foundation plan's Global Constraints still holds. In particular:
- class-file v48;
- never log or read the j9 command line;
- `/tmp` is `/dev/shmem`: files only, no directories;
- the unit has no `date`, `tr`, `md5sum`, `cksum`, `find`, `stat`, `unzip` or python;
- bench SSH needs `-o HostKeyAlgorithms=ssh-rsa -o PubkeyAcceptedKeyTypes=ssh-rsa`.

The rest are specific to this plan:

**On-unit paths**
- **SDK install root** `BASE = /mnt/app/eso/hmi/lsd/mibsdk/`: `sdk.properties`, `grants.properties`, `SAFE_MODE`, `permissions.json` (new), `plugins/<id>.jar`, `plugins/<id>.reload`; legacy `stock/` (old `mibsdk-dev` backup) and legacy `data/`, `ledger.properties{,.tmp}`, `cache`, `boot.state{,.tmp}` (pre-move state).
- **Persist root** `PERSIST = /mnt/persist/mibsdk/`: `boot.state`, `ledger.properties`, `plugins.properties` (user on/off: one `<id>.off=true` line per plugin switched off; `UserToggles` via `DurableFiles`), `data/<id>.properties`. Writable without a remount.
- **RAM root**: `/ramdisk/mibsdk/`.
- **SDK jars**: `/mnt/app/eso/hmi/lsd/jars/{mibsdk-runtime,mibsdk-adapter-hqc,mibsdk-rt-retrotranslator}.jar`.
- **Bundle list**: `/mnt/app/eso/hmi/lsd/bundles_dsitracer_enabled.properties` (stock mode 0777). Marker: `/mnt/app/eso/hmi/lsd/dsitracer_activated`. Tracer jar: `/mnt/app/eso/hmi/lsd/DSITracer.jar`.
- **HMI version**: the first `HMI = …` line of `/ifs/version_info.txt`, value after `=`, all whitespace removed. Supported: `HMI_AU_MIB2_HQC_NAR_G22_C10_180803`.
- **Log**: `/tmp/mibsdk.log`. Dev channel polls every `dev.poll.ms` (default 2000). Ack lines: `dev channel: reload requested for <id>` and `STATUS <id> <STATE> <reason>`.

**Values moved out of `gen_bundles.py` / `mibsdk-dev` (exact)**
- configurator `mibsdk.adapter.hqc.ChainingConfigurator`; bundle line `MibSdk=mibsdk.adapter.hqc.SdkBundleActivator`; tracer configurator `de.audi.tghu.dsitrace.TraceConfigurator`; tracer bundle name `DSITracer`.
- `HMICar=` stock line `HMICar=de.audi.tghu.car.hmi.Activator`; SDK line `HMICar=mibsdk.adapter.hqc.ui.SdkCarActivator`.
- `sdk.properties` lines after the optional `chain.configurator=`: `hook.services=org.dsi.ifc.carparkingsystem.DSICarParkingSystem`, `ui.enabled=true`.
- Owned class prefixes (sole-owner rule): `de/audi/tghu/car/hmi/`, `de/audi/atip/model/CarModelBank`.
- Spike jars: `lsd/jars/mibsdk-spike-*.jar`.
- `BOOT_STATE_RESET` = `unhealthyBoots=0\nlastShutdownClean=true\nwrapBoots=0\n`.

**API range**
- `SdkInfo.MIN_API_VERSION = 1`, `SdkInfo.API_VERSION = 2` (`api/src/main/java/mibsdk/api/SdkInfo.java`); `PluginJson` mirrors them as `MIN_API`/`MAX_API`; the runtime's `ManifestParser` enforces the same range.
- mibpkg's compatibility rule everywhere: `sdk.min_api <= plugin.api <= sdk.api`.

**Permissions**
- Catalog = the constants of `api/src/main/java/mibsdk/api/Permissions.java`: `vehicle.read`, `vehicle.parking.write`, `hooks.parking`, `platform.unrestricted`, `ui.slot.carmenu`, `ui.slot.statusbar`, `network`.
- A permission string matches `[A-Za-z0-9._-]+`.
- **Runtime enforcement is all-or-nothing today**: a plugin whose declared permissions are not all granted is `BLOCKED_PERMISSIONS` and never runs (`SdkRuntime`). So revoking one permission stops the plugin. Every revoke surface says so. **Follow-up (not in this plan): optional permissions** (a manifest-level `optional` list the runtime tolerates ungranted). The approval sheet renders one row per permission id so a per-row checkbox can be added later without changing the JSON shape.

**Grants and toggles files**
- `grants.properties`: one `<id>=<perm>,<perm>` line per plugin, parsed by Java `Properties` in `Grants.load`. mibpkg rewrites only the line whose key is **exactly** `<id>` (comment lines skipped as keys) and keeps every other line byte for byte (port of `GRANTS_PY`). The same line editor drops `<id>.off` from `plugins.properties`.

**Ledger v2** (`OverrideLedger`)
- One `<key>=<pluginId>|<scope>|<prior>|<active>|<A|R>` line per override (`A` held, `R` restoring); a 4-field line (no state) is legacy and counts as held. No comments.
- Read from `PERSIST/ledger.properties` **and** the old `BASE/ledger.properties`. A plugin's entries are the lines containing `=<id>|` (exact id, so `bike` never matches `bike-rack`).

**Paths in mibpkg code**
- Every mibpkg path goes through `Layout::p(abs)`, so tests run under a temp root.
- New constants live in `src/mibsdk/mod.rs`. Nothing else in mibpkg names an SDK path.

**Package identity**
- The SDK package is named `mibsdk`. The name `mibsdk` is refused for any manifest without `[sdk]`; it is **not** added to `RESERVED` (that would refuse the SDK package itself).
- A plugin package's `[package].name` **is** the plugin id: 2–40 characters, first `[a-z0-9]`, the rest `[a-z0-9-]`. The Gradle build is stricter (`[a-z0-9][a-z0-9-]{1,39}`, i.e. ≤ 40) than the runtime (`{1,40}`, ≤ 41): a 41-character id still loads when hand-pushed but can't be packaged. Intended; documented in the README (Task 11).
- Versions are semver (mibpkg rule). `hello` and `crashy` move from `"1.0"` to `"1.0.0"`; `bike-rack`, `hello-menu`, `daemon-status` already are `1.0.0`.

**Mount discipline**
- Every write to `/mnt/app` goes through `mibsdk::put*` (Task 4) under `ctx.mounts.rw("/mnt/app")`. Nothing in mibpkg runs a bare `mount`.
- **Remount lock**: `/tmp/mibpkg.rwlock`. **Holder files**: `/tmp/mibpkg.rw.<tag>.<pid>`, `<tag>` = `app` | `system`. Package names `rw` and `rwlock` are reserved.
- One `Ctx` per process (holders are per pid); the supervisor and its web threads share one `Ctx`.
- No `/mnt/app` rw guard is held while waiting for a runtime ack or the ledger.

**Approval and data**
- **No silent grants**: a plugin install or update that adds a permission needs an explicit approval list that covers it. `update-all` never approves on the user's behalf. Seeding never installs `[sdk]` or `[plugin]` packages (see Task 8). **[my call]**
- **Plugin data**: plain remove **keeps** `PERSIST/data/<id>.properties`, `--purge` (alias `--purge-data`) deletes it after the runtime confirms the unload. **[my call]**
- **Remove drops `<id>.off`** from `plugins.properties` on every remove (as `mibsdk-dev remove` does), so a reinstalled plugin starts on.

**Dependencies, version and authorship**
- **No new crates** in mibpkg **[my call]**; the owned-classes guard uses a std-only zip central-directory reader.
- mibpkg's version becomes `0.8.0` (Task 11).
- **No personal names or paths in committed files.** Use `~`/`$HOME`.

## Review Focus

1. **Another writer ends its rw section mid-install.** The web server's `gem-screens` redraw, `record_result` after a GEM action, `online-check`'s cache write, an old 0.7 supervisor, or a tool that ignores the holder protocol remounts `/mnt/app` read-only while a plugin install is writing. mibpkg holders keep it rw against mibpkg processes; `put` re-asserts, verifies by read-back and retries once against the rest. A holder left by a crashed process must not pin it rw forever. **Tests:** Task 1 `other_live_holder_keeps_app_rw`, `dead_holder_is_ignored_and_swept`; Task 4 `put_retries_once_after_an_outside_ro_remount`.
2. **Update after a revoke.** An update adds `network`, and the user had earlier revoked `vehicle.read`. Only `network` is asked for, `vehicle.read` stays revoked, and because enforcement is all-or-nothing the result says the plugin is blocked and how to re-grant. **Test:** Task 8 `plugin_update_asks_only_new_permissions_and_keeps_revocations`.
3. **First mibpkg SDK install on a unit set up by the old `mibsdk-dev`** (the bench). The live bundles file is generated (with the SDK `HMICar=` line), `BASE/stock/` holds the stock file, and old-layout state may sit under `BASE`. mibpkg adopts `stock/` as the backup, never backs up the modified file as "stock", and migrates old state without overwriting. **Tests:** Task 7 `sdk_install_adopts_mibsdk_dev_stock_backup`, `sdk_install_migrates_old_layout_state`.
4. **Purge while the SDK is running.** The plugin's `onDisable` flushes its storage, so the data file could reappear. The file is deleted only after the runtime confirms the unload; without a confirmation it is kept and the result says so. **Test:** Task 8 `plugin_purge_waits_for_unload_before_deleting_data`.
5. **An id already on the unit as a dev push.** Installing a package over a `mibsdk-dev push`ed jar takes it over (the package owns the id afterwards); `mibpkg sdk remove <id>` removes a pushed plugin, and SDK uninstall refuses while pushed jars remain unless `--force`. **Tests:** Task 8 `plugin_install_takes_over_a_pushed_jar`, `sdk_remove_handles_a_pushed_plugin`; Task 7 `sdk_remove_refuses_with_pushed_plugins_unless_force`.

## Test port map (old Python tests → Rust)

Every Python test deleted in Task 14 gets a Rust test whose body starts with `// was: <python test name>`. Task 14 checks the mapping is complete.
- `installer/test_gen_bundles.py` → Task 6 `tests/mibsdk_bundles.rs`: `test_tracer_active`, `test_tracer_inactive`, `test_other_lines_unchanged`, `test_sdkprops_without_chain`, `test_hmicar_substituted_once_keeping_crlf`, `test_nonstock_hmicar_refused`, `test_generate_is_fixed_point`, `test_sdkprops_enables_ui`, `test_idempotent_on_already_generated`, `test_multiple_active_configurator_lines_refused` (`test_cli_runs` is dropped with the CLI).
- `installer/test_mibsdk_dev.py` →
  - Task 4 (stamp, `put`): `test_refuses_a_dist_without_a_stamp`, `test_refuses_a_jar_that_does_not_match_its_stamp`, `test_refuses_a_stamp_with_a_duplicate_key_or_no_checks`, `test_refuses_a_jar_without_a_stamp`, `test_refuses_a_stamp_for_another_id`, `test_refuses_a_stale_stamp`, `test_warns_loudly_for_an_unrestricted_plugin`, `test_a_restricted_plugin_gets_no_warning`, `test_push_survives_another_process_remounting_app_read_only`, `test_a_move_that_fails_with_status_0_fails_the_push`, `test_a_failed_push_leaves_no_temp_file`, `test_install_sweeps_stale_push_temp_files`.
  - Task 7 (SDK package): `test_tracer_off_unit_does_not_get_dsitracer_autostarted`, `test_tracer_on_unit_keeps_dsitracer_chained`, `test_reinstall_uses_the_recorded_stock_state`, `test_creates_the_persistent_root_and_no_runtime_state_on_app`, `test_reinstall_moves_state_left_on_app_by_an_older_install`, `test_reinstall_never_overwrites_persistent_state`, `test_old_ledger_entries_are_merged_into_an_existing_ledger`, `test_old_ledger_entry_that_conflicts_is_kept_where_it_is`, `test_failed_ledger_copy_loses_nothing_and_a_rerun_migrates_it`, `test_failed_data_copy_loses_nothing_and_a_rerun_migrates_it`, `test_data_move_that_fails_with_status_0_keeps_the_old_data`, `test_refuses_when_carplay_stock_files_exist_anywhere_under_hmi`, `test_refuses_jar_defining_owned_class`, `test_ignores_sdk_jars_in_ownership_guard`, `test_refuses_while_spike_jar_present`, `test_guard_checks_every_jar_not_just_the_first`, `test_guard_only_looks_at_jars`, `test_guard_fails_closed_on_an_unreadable_jar`, `test_guard_refuses_a_symlinked_jar`, `test_guard_refuses_a_jar_name_with_a_space`, `test_nonstock_hmicar_changes_nothing_at_all`, `test_upgrade_refuses_a_foreign_live_hmicar`, `test_upgrade_with_the_sdk_value_live_is_fine`, `test_refuses_a_backup_that_already_holds_the_sdk_value`, `test_install_points_hmicar_at_the_sdk_keeping_crlf`, `test_refuses_a_nonstock_hmicar_line`, `test_refuses_while_an_override_is_in_force`, `test_refusal_shows_a_pending_restore_line`, `test_force_uninstalls_with_a_loud_warning`, `test_refuses_on_an_override_left_in_the_old_ledger_location`, `test_default_uninstall_keeps_plugin_data_purge_removes_it`, `test_uninstall_restores_the_bundles_file_mode_and_leaves_the_marker_alone`, `test_uninstall_restores_hmicar_byte_identical`, `test_backup_without_a_recorded_mode_restores_the_stock_mode_0777`, `test_empty_or_missing_ledger_uninstalls`.
  - Task 8 (plugin remove): `test_removes_the_jar_and_grant_and_requests_a_reload`, `test_drops_the_plugins_off_switch`, `test_plugins_properties_is_pushed_before_the_reload_marker`, `test_leaves_a_plugin_with_a_longer_id_alone`, `test_keeps_data_unless_purge_data`, `test_reports_overrides_still_in_the_ledger`, `test_a_rerun_after_the_reload_request_failed_finishes_the_removal`, `test_a_plugin_known_only_by_its_ledger_lines_is_removed`, `test_refuses_an_unknown_plugin`.
  - Task 9 (CLI verbs): `test_each_push_writes_a_fresh_reload_marker`, `test_off_writes_boot_guard_reset_content`, `test_set_wrap_keeps_other_keys`, `test_set_wrap_without_a_file_uses_the_reset_keys`, `test_set_wrap_rejects_a_bad_count`, `test_prints_the_installed_hmicar_line`, `test_missing_hmicar_line_and_unreadable_bundles`, `test_removes_only_that_plugins_data`, `test_rejects_a_bad_id`.
  - Stays in Python (Task 14, transport snapshot): both `test_pushes_the_bytes_it_verified_not_a_jar_rewritten_afterwards`.

---

## File Structure

```
mibpkg/  (worktree branch feat/mibsdk-packages)
├── src/rwhold.rs            NEW  cross-process /mnt/app|/mnt/system rw holders + remount lock + reassert
├── src/ctx.rs               MOD  Mounts delegates first-acquire/last-release to rwhold; reassert(); Ctx.approved, Ctx.force
├── src/lock.rs              MOD  Lock::acquire_at(path, wait) shared by the action lock and rwlock
├── src/backups.rs           MOD  absence tombstones (<encoded>.absent), forget(), has_backup()
├── src/manifest.rs          MOD  [plugin] / [sdk] tables, kind(), validation, RESERVED += rw, rwlock; `mibsdk` only with [sdk]
├── src/archive.rs           MOD  pack() requires the [plugin]/[sdk] jars, stamps and permissions file
├── src/index.rs             MOD  IndexEntry.kind, IndexEntry.permissions
├── src/unit.rs              MOD  hmi_version(layout)
├── src/mibsdk/mod.rs        NEW  SDK paths, installed_sdk(), sdk_running(), reboot flag, put/put_file (F8 write discipline)
├── src/mibsdk/stamp.rs      NEW  CheckStamp verify (port of STAMP_PY)
├── src/mibsdk/catalog.rs    NEW  permissions.json (plain words, loud flag)
├── src/mibsdk/propsline.rs  NEW  exact-key line set/drop (port of GRANTS_PY), for grants.properties and plugins.properties
├── src/mibsdk/grants.rs     NEW  grants read/set/drop; approval arithmetic
├── src/mibsdk/toggles.rs    NEW  plugins.properties <id>.off read/drop
├── src/mibsdk/ledger.rs     NEW  ledger v2 reader (both files), migrate merge (port of LEDGER_PY)
├── src/mibsdk/bundles.rs    NEW  port of gen_bundles.py (incl. HMICar=), sdk.properties render, check_hmicar
├── src/mibsdk/zipnames.rs   NEW  std-only zip central-directory entry names
├── src/mibsdk/ack.rs        NEW  wait for the runtime's reload confirmation in /tmp/mibsdk.log
├── src/mibsdk/sdk_pkg.rs    NEW  [sdk] checks, migrate_state, deploy (all-or-undo), undeploy
├── src/mibsdk/plugin_pkg.rs NEW  [plugin] checks, approval, deploy, activate, remove_id (packages and pushed ids)
├── src/install.rs           MOD  kind hooks in install_package / remove / rollback / update_all
├── src/supervisor.rs        MOD  seed_once skips [sdk] and [plugin] packages
├── src/cli.rs               MOD  --approve=, --force, --purge-data alias, top-level install-file, `sdk` verbs, exit code 3
├── src/web/{api,actions,routes,upload}.rs, src/catalog.rs  MOD  permissions in views; approve and grant actions
├── web/js/views/{package,upload}.js, web/js/permissions.js NEW, web/tests/permissions.test.mjs NEW
├── tests/{rwhold,backups_absent,manifest_kinds,mibsdk_formats,mibsdk_bundles,sdk_pkg,plugin_pkg,cli_sdk}.rs NEW
├── tests/fixtures/mibsdk/    NEW  bundles_dsitracer_enabled.properties (from the SDK repo's installer/testdata), stamps, catalog
└── Cargo.toml, packaging/mibpkg/manifest.toml.in, README.md, docs/unit-facts.md  MOD (0.8.0, docs)

mib2q-plugin-sdk/  (feat/ui)
├── api/permissions.json                         NEW  permission catalog (single source of the plain words)
├── api/src/test/java/mibsdk/api/PermissionCatalogTest.java  NEW
├── build-logic/src/main/java/mibsdk/build/PluginJson.java   MOD  name, version, api fields; mibpkg-compatible id/version checks
├── build-logic/src/main/java/mibsdk/build/MpkStage.java     NEW  renders manifest.toml for [plugin] / [sdk]
├── build-logic/src/main/java/mibsdk/build/MibpkgBuildTask.java NEW  runs host `mibpkg build`
├── build-logic/src/main/java/mibsdk/build/MibPluginPlugin.java MOD  mibPackage task (dependsOn mibPluginJar)
├── build-logic/src/main/java/mibsdk/build/Toolchain.java    MOD  mibpkg(Project) from -Pmibsdk.mibpkg
├── build-logic/build.gradle.kts                  MOD  pass mibsdk.mibpkg into TestKit tests
├── build-logic/src/test/java/mibsdk/build/{PluginJsonTest,MpkStageTest}.java NEW/MOD
├── dist/build.gradle.kts                         MOD  mibpkgPackage → build/dist/mibsdk-<version>.mpk (stale ones deleted)
├── gradle.properties                             MOD  mibsdk.version=0.1.0
├── plugins/{hello,crashy}/src/main/resources/plugin.json  MOD  version 1.0.0
├── installer/mibsdk-dev                          MOD  delegate to mibpkg; keep log/restart
├── installer/test_mibsdk_dev.py                  MOD  transport tests against a fake on-unit mibpkg
├── installer/gen_bundles.py, installer/test_gen_bundles.py  DELETE (ported in Task 6)
└── docs/…  spec §5.3/§7/§10 notes, car-readiness follow-ups, bench-e2e Plan 3 section
```

---

### Task 1: Cross-process read-write mount holders (fixes F8) — host-only

**Files:**
- Create: `src/rwhold.rs`, `tests/rwhold.rs`
- Modify:
  - `src/lock.rs`: extract `Lock::acquire_at(path: &Path, sys: &dyn Sys, wait: Duration) -> Result<Lock>`; `acquire` calls it with `layout.run("lock")` and `Duration::ZERO`.
  - `src/ctx.rs:10-48`: `Mounts` holds `run_dir: PathBuf` and `holder: u32`.
  - `src/manifest.rs:11`: `RESERVED` gains `"rw"`, `"rwlock"` (not `"mibsdk"`; see Task 3).
  - `src/lib.rs`

**Interfaces:**
- Produces:
  - `pub fn rwhold::acquire(layout: &Layout, sys: &dyn Sys, mount: &str, holder: u32) -> Result<()>`. Under the remount lock it creates holder file `layout.run(&format!("rw.{tag}.{holder}"))`, then always runs `sys.remount(mount, true)`.
  - `pub fn rwhold::release(layout: &Layout, sys: &dyn Sys, mount: &str, holder: u32) -> Result<()>`. Under the lock it removes its own holder file and every holder whose pid is dead; it calls `sys.remount(mount, false)` only if no live holder of that tag remains.
  - `pub fn rwhold::reassert(layout: &Layout, sys: &dyn Sys, mount: &str) -> Result<()>`: under the lock, `sys.remount(mount, true)`; holder files untouched.
  - `pub fn rwhold::tag(mount: &str) -> Result<&'static str>`: `"/mnt/app"` → `"app"`, `"/mnt/system"` → `"system"`, else error.
  - `pub const RWLOCK_WAIT: Duration = Duration::from_secs(3)`.
  - `Mounts::new(sys, layout)`, test-only `Mounts::with_holder(sys, layout, holder: u32)`, and `Mounts::reassert(&self, mount: &str) -> Result<()>` (errors unless this process holds `mount`). `Ctx::new` passes `&layout` and `std::process::id()`. The in-process refcount stays: `acquire` only on 0→1, `release` only on 1→0.
  - Module doc of `rwhold`: holders are per pid, so one `Ctx` per process (the supervisor's web threads share it); a tool outside the protocol can still remount ro, which is why writes go through `mibsdk::put` (Task 4).

- [ ] **Step 1: Write the failing tests** in `tests/rwhold.rs`, using `common::fixture()` and `RecordingSys`:
```rust
#[test] fn other_live_holder_keeps_app_rw() {
    // holder A = std::process::id(); holder B = a spawned `sleep 30` child's pid via Mounts::with_holder.
    // A acquires, B acquires, A releases:
    assert_eq!(*sys.calls.borrow(), vec!["rw /mnt/app", "rw /mnt/app"]);   // no "ro"
    // B releases:
    assert_eq!(sys.calls.borrow().last().unwrap(), "ro /mnt/app");
}
#[test] fn dead_holder_is_ignored_and_swept() { /* tmp/mibpkg.rw.app.<reaped pid>; acquire+release → ends "ro /mnt/app"; file gone */ }
#[test] fn nested_guards_in_one_process_touch_the_registry_once() { /* two guards, drop inner: holder file stays; drop outer: gone */ }
#[test] fn reassert_remounts_rw_without_touching_holders() { /* holder count unchanged; calls end "rw /mnt/app" */ }
#[test] fn reassert_without_a_guard_is_an_error() {}
#[test] fn remount_lock_held_by_live_pid_times_out_with_busy() { /* errs "busy" within RWLOCK_WAIT + 1 s */ }
#[test] fn stale_remount_lock_is_taken_over() {}
#[test] fn unknown_mount_is_rejected() { assert!(rwhold::tag("/mnt/persist").is_err()); }
```
- [ ] **Step 2: Run them and confirm they fail.** Run `cargo test --test rwhold`. Expected: compile error, `rwhold` doesn't exist.
- [ ] **Step 3: Implement `rwhold` and refactor `Lock` and `Mounts`.** The remount lock is `layout.run("rwlock")`, taken with `Lock::acquire_at(.., RWLOCK_WAIT)`, polling every 50 ms. Liveness via `sys.process_alive`. `Drop for RwGuard` logs a release error with `crate::warn!` and never panics.
- [ ] **Step 4: Run the whole suite.** Run `cargo test`. Expected: all pass; existing `vec!["rw /mnt/app", "ro /mnt/app"]` assertions in `tests/install.rs` stay true (single holder).
- [ ] **Step 5: Commit.** `git add -A src tests && git commit -m "feat: refcount /mnt/app read-write across processes (holder files + remount lock)"`

### Task 2: Backups record absent files and can be forgotten — host-only

**Files:**
- Modify: `src/backups.rs`
- Test: `tests/backups_absent.rs`

**Interfaces:**
- Produces:
  - `backup_once(layout, unit_abs)` on a **missing** source writes the tombstone `backup_path(..) + ".absent"` (empty file) instead of failing.
  - `restore(layout, unit_abs)` with a tombstone deletes the target ("already gone" is success).
  - `pub fn forget(layout: &Layout, unit_abs: &str) -> Result<()>` removes the backup and/or the tombstone.
  - `pub fn has_backup(layout: &Layout, unit_abs: &str) -> bool` is true for either form.
  - `pub fn is_tombstone(layout: &Layout, unit_abs: &str) -> bool`.
  - `restore_all` handles tombstones; it decodes the name without the `.absent` suffix.

- [ ] **Step 1: Write the failing tests.** `missing_file_backs_up_as_tombstone_and_restore_deletes_it`, `existing_backup_is_never_overwritten` (two `backup_once` calls with a changed file between; the first content is restored), `forget_removes_both_forms`, `restore_all_handles_tombstones`, `mode_is_preserved` (0o777 restores 0o777).
- [ ] **Step 2: Run them and confirm they fail.** Run `cargo test --test backups_absent`. Expected: FAIL; a missing source errors today.
- [ ] **Step 3: Implement it in `src/backups.rs`.**
- [ ] **Step 4: Run the tests and confirm they pass.** Run `cargo test --test backups_absent && cargo test --test hardening`. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(backups): tombstones for files absent before an edit; forget()`

### Task 3: Manifest kinds `[plugin]` and `[sdk]`, index fields, pack checks — host-only

**Files:**
- Modify: `src/manifest.rs` (`validate` at `:134`), `src/archive.rs:36-43` (`pack`), `src/index.rs:33-45` and `index_dir` (`:169-205`)
- Test: `tests/manifest_kinds.rs`, `tests/index.rs`

**Interfaces:**
- Produces, in `src/manifest.rs`:
  ```rust
  pub struct PluginSpec { pub jar: String, pub api: u32, pub permissions: Vec<String> }      // [plugin]
  pub struct SdkSpec {                                                                        // [sdk]
      pub api: u32, pub min_api: u32, pub hmi: Vec<String>,
      pub jars: Vec<String>, pub stamp: String,            // stamp = "mibsdk-unit.stamp" (one for all jars)
      pub configurator: String, pub bundle: String, pub tracer_configurator: String,
      pub hmicar_stock: String, pub hmicar: String,        // class names; the line is "HMICar=<value>"
      pub owned: Vec<String>,                              // class path prefixes, e.g. "de/audi/tghu/car/hmi/"
      #[serde(default)] pub properties: Vec<String>, pub permissions: String }
  pub enum Kind { Plain, Plugin, Sdk }
  Manifest { …, #[serde(default)] pub plugin: Option<PluginSpec>, #[serde(default)] pub sdk: Option<SdkSpec> }
  impl Manifest { pub fn kind(&self) -> Kind }
  ```
- Validation (all `bail!` with the quoted words):
  - Both tables: "a package is either [plugin] or [sdk]".
  - `[plugin]`/`[sdk]` with `[[file]]`, `[[service]]`, `[[config]]` or `[scripts]`: "plugin and sdk packages are declarative: no files, services, configs or scripts".
  - `[plugin]` with a name shorter than 2: "plugin id … must be 2-40 characters".
  - `[sdk]` with `[package].name != "mibsdk"`: "the SDK package must be named mibsdk".
  - name `mibsdk` without `[sdk]`: "the name mibsdk is reserved for the SDK package".
  - `[plugin].jar`, each `[sdk].jars` entry, `[sdk].stamp` and `[sdk].permissions` are single path components passing `safe_rel`; jars end `.jar`, `stamp` ends `.stamp`, `permissions` ends `.json`. The plugin's stamp is implicitly `<jar>.stamp`.
  - `1 <= min_api <= api`; `[plugin].api >= 1`.
  - Each permission matches `[A-Za-z0-9._-]+`; `bundle` contains `=`; `hmicar_stock`, `hmicar` match `[A-Za-z_$][A-Za-z0-9_$.]*`; each `owned` entry is non-empty, matches `[A-Za-z0-9_$/]+` and doesn't start with `/`.
- `archive::pack` also requires, for `[plugin]`, `<jar>` and `<jar>.stamp`; for `[sdk]`, every jar, `stamp` and `permissions`. Same message as today: "<n> is named in manifest.toml but missing from <dir>".
- `IndexEntry` gains `#[serde(default, skip_serializing_if = "Option::is_none")] pub kind: Option<String>` (`"plugin"`|`"sdk"`) and `#[serde(default, skip_serializing_if = "Vec::is_empty")] pub permissions: Vec<String>`; `index_dir` fills both. `IndexEntry` has no `deny_unknown_fields` (`index.rs:32`), so 0.7 readers ignore them. 0.7 itself refuses a `[plugin]` manifest with a generic parse error (`deny_unknown_fields` on `Manifest`); the README says so (Task 11).

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
  - `sdk_manifest_parses` with every `SdkSpec` field at the Global Constraints values (`api=2`, `min_api=1`, `stamp="mibsdk-unit.stamp"`).
  - One test per refusal above, asserting the message substring; `plain_manifest_named_mibsdk_is_refused`; `sdk_manifest_named_mibsdk_is_accepted`.
  - `pack_refuses_plugin_without_stamp`, `pack_refuses_sdk_without_unit_stamp`, `pack_refuses_sdk_without_permissions_json`.
  - `index_entry_carries_kind_and_permissions_and_omits_them_for_plain_packages`.
- [ ] **Step 2: Run them and confirm they fail.** Run `cargo test --test manifest_kinds --test index`. Expected: FAIL with "unknown field `plugin`".
- [ ] **Step 3: Implement the kinds, the validation, the pack checks and the index fields.**
- [ ] **Step 4: Run the whole suite.** Run `cargo test`. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(manifest): declarative [plugin] and [sdk] package kinds; pack checks; kind/permissions in index.json`

### Task 4: SDK facts — paths, HMI version, check stamp, F8 write discipline — host-only

**Files:**
- Create: `src/mibsdk/mod.rs`, `src/mibsdk/stamp.rs`
- Modify: `src/unit.rs`, `src/lib.rs`
- Test: `tests/mibsdk_formats.rs`, `tests/fixtures/mibsdk/`

**Interfaces:**
- `src/mibsdk/mod.rs` constants: `BASE = "/mnt/app/eso/hmi/lsd/mibsdk"`, `PERSIST = "/mnt/persist/mibsdk"`, `RAM = "/ramdisk/mibsdk"`, `LSD`, `JARS`, `BUNDLES`, `MARKER`, `TRACER_JAR`, `HMI_ROOT = "/mnt/app/eso/hmi"`, `LOG = "/tmp/mibsdk.log"`, `SDK_PKG = "mibsdk"`, `BOOT_STATE_RESET` (Global Constraints values).
- Functions:
  - `pub fn installed_sdk(layout: &Layout) -> Option<(String /*version*/, SdkSpec, PathBuf /*ver dir*/)>` via `install::current_manifest`.
  - `pub fn reboot_flag(layout) -> PathBuf` = `layout.run("sdk-reboot")`.
  - `pub fn sdk_running(layout) -> bool`: `LOG` exists and the reboot flag does not.
  - `pub fn put(ctx: &Ctx, dst_abs: &str, bytes: &[u8], mode: u32) -> Result<()>` and `pub fn put_file(ctx: &Ctx, dst_abs: &str, src: &Path, mode: u32) -> Result<()>`: write `<dir>/.mibpkg.tmp`, read it back and compare, rename onto the target, read the target back and compare; on any failure (EROFS, a rename that "succeeds" without replacing, a mismatch) `ctx.mounts.reassert("/mnt/app")` and retry once; the temp file is removed on every exit path; a stale `.mibpkg.tmp` in the directory is removed first. For a `dst_abs` under `/mnt/app` it re-asserts rw before the first attempt too; other paths skip the reassert. The caller holds `ctx.mounts.rw("/mnt/app")` for `/mnt/app` targets.
  - Test seam: `#[doc(hidden)] pub fn put_with(ctx, dst_abs, bytes, mode, ops: &dyn FileOps) -> Result<()>` with `pub trait FileOps { fn write(&self, p: &Path, b: &[u8], mode: u32) -> io::Result<()>; fn read(&self, p: &Path) -> io::Result<Vec<u8>>; fn rename(&self, a: &Path, b: &Path) -> io::Result<()>; fn remove(&self, p: &Path) -> io::Result<()>; }`; `put` uses the std implementation.
- `pub fn unit::hmi_version(layout: &Layout) -> Option<String>`.
- `src/mibsdk/stamp.rs` (port of `STAMP_PY`):
  - `pub enum StampKind { Plugin, Unit }` (`"plugin"`, `"unit"`);
  - `pub struct Verified { pub unrestricted: Option<bool> }` (`Some` for plugins);
  - `pub fn verify(stamp_file: &Path, kind: StampKind, expected_id: Option<&str>, jars: &[&Path]) -> Result<Verified>`. Rules: decode ISO-8859-1; drop one trailing empty line; every line has `=` and no key repeats, the first line is exactly `format=1`, `checks` is non-empty — else `format`; `kind` equal — else `kind`; plugin: `plugin.id == expected_id` — else `id <got|None> != <want>`; plugin: `plugin.unrestricted` ∈ {`true`,`false`} — else `format`; for each jar by file name, `sha256.<name>` present — else `no hash for <name>`; equal to the jar's SHA-256 — else `hash mismatch for <name>`; unreadable stamp or jar — `missing`. Unknown keys are ignored. The error's text is exactly the reason; callers wrap it:
    - plugin: `"<id>.jar has no valid check stamp (<why>); only jars built by the mibsdk.plugin convention can be installed: ./gradlew :<project>:mibPluginJar (M7)"`;
    - SDK: `"the mibsdk package's jars are not a checked build (<why>); build it with ./gradlew :dist:mibpkgPackage; nothing changed (M7)"`;
    - plugin with `unrestricted == Some(true)`: the result notes `"WARNING: <id> declares platform.unrestricted: it may use reflection, exec or exit; the SDK cannot contain it"`.

- [ ] **Step 1: Write the failing tests** (each ported one carries `// was: …` per the test port map).
  - `hmi_version_reads_the_hmi_line` (fixture lines `MU = …` and `HMI = HMI_AU_MIB2_HQC_NAR_G22_C10_180803 `), `hmi_version_none_without_file`.
  - Stamp, with fixtures produced in the exact `CheckStamp` layout: `stamp_accepts_plugin_stamp_and_reports_unrestricted`, `stamp_accepts_unit_stamp_for_three_jars`, `stamp_missing_file_is_missing`, `stamp_rejects_wrong_kind` (a plugin stamp offered as `unit`), `stamp_rejects_duplicate_key_or_empty_checks` (`format`), `stamp_rejects_first_line_not_format_1`, `stamp_rejects_other_plugin_id` (`id other != hello`), `stamp_rejects_hash_mismatch_of_one_of_three_jars` (`hash mismatch for mibsdk-adapter-hqc.jar`), `stamp_rejects_stale_stamp_after_jar_edit`, `stamp_rejects_jar_without_hash_line` (`no hash for x.jar`), `stamp_ignores_unknown_keys`, `stamp_restricted_plugin_has_no_warning`.
  - `put`: `put_writes_and_reads_back`, `put_retries_once_after_an_outside_ro_remount` (a `FileOps` fake whose first rename returns Ok but leaves the target unchanged; the retry succeeds; `RecordingSys` calls contain two `rw /mnt/app`), `put_fails_after_second_failure_and_leaves_no_temp_file`, `put_removes_a_stale_temp_file_first`, `put_outside_mnt_app_does_not_remount`.
  - `installed_sdk_none_until_installed`.
- [ ] **Step 2: Run them and confirm they fail.** Run `cargo test --test mibsdk_formats`. Expected: FAIL (module missing).
- [ ] **Step 3: Implement `mibsdk/mod.rs`, `stamp.rs` and `unit::hmi_version`.**
- [ ] **Step 4: Run the tests and confirm they pass.** Run `cargo test --test mibsdk_formats`. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(mibsdk): SDK paths, HMI version, CheckStamp verification, verified rw writes`

### Task 5: Permission catalog, grants and toggles files, approval arithmetic, ledger v2 — host-only

**Files:**
- Create: `src/mibsdk/catalog.rs`, `src/mibsdk/propsline.rs`, `src/mibsdk/grants.rs`, `src/mibsdk/toggles.rs`, `src/mibsdk/ledger.rs`
- Test: `tests/mibsdk_formats.rs` (extend)

**Interfaces:**
- **Catalog**: `pub struct PermInfo { pub id: String, pub group: String, pub text: String, pub loud: bool }`; `pub fn catalog::parse(json: &str) -> Result<Vec<PermInfo>>` accepting `{"format":1,"api":N,"min_api":M,"permissions":[…]}`; `pub fn catalog::installed(layout) -> Option<Vec<PermInfo>>` (reads `BASE/permissions.json`); `pub fn catalog::describe(cat: Option<&[PermInfo]>, ids: &[String]) -> Vec<String>`: `"<text> (<id>)"`, prefixed `"WARNING: "` when `loud`, or `"<id> (unknown to the installed SDK)"`.
- **Line editor** (port of `GRANTS_PY`): `pub fn propsline::set(text: &str, key: &str, value: &str) -> String` and `pub fn propsline::drop(text: &str, key: &str) -> (String, bool /*dropped*/)`. A line's key is the trimmed text before the first `=`, for lines not starting (after leading whitespace) with `#`; only a key exactly equal to `key` matches; `set` replaces the first match and drops later ones, or appends; output is the kept lines joined by `\n` plus a final `\n` (empty output for no lines). UTF-8.
- **Grants**: `pub fn grants::read(layout) -> Result<BTreeMap<String, BTreeSet<String>>>` (missing file = empty); `pub fn grants::set(ctx, id: &str, perms: &BTreeSet<String>) -> Result<()>` and `pub fn grants::drop_line(ctx, id) -> Result<bool>`, both through `propsline` and `mibsdk::put` (caller holds rw).
- **Toggles**: `pub fn toggles::off_ids(layout) -> BTreeSet<String>` (lines `<id>.off=true` of `PERSIST/plugins.properties`; unreadable = empty, as `UserToggles`); `pub fn toggles::drop(ctx, id) -> Result<bool>` drops key `<id>.off` via `propsline::drop` and writes with `put` (no rw needed, `PERSIST`).
- **Approval**: `pub struct Approval { pub ask: Vec<String>, pub grant: BTreeSet<String> }`; `pub fn grants::plan(declared_new: &[String], declared_old: Option<&[String]>, current: &BTreeSet<String>) -> Approval` with `ask = declared_new − declared_old` (all of `declared_new` when `None`) and `grant = (current ∩ declared_new) ∪ ask`. `pub fn grants::missing(declared: &[String], grant: &BTreeSet<String>) -> Vec<String>` (what makes the plugin `BLOCKED_PERMISSIONS`).
- **Ledger** (v2):
  - `pub struct LedgerEntry { pub key: String, pub plugin: String, pub state: char /*'A'|'R'*/, pub line: String, pub source: PathBuf }`;
  - `pub fn ledger::read(layout) -> Result<Vec<LedgerEntry>>` reads `PERSIST/ledger.properties` then `BASE/ledger.properties`; blank lines skipped; 5 fields → state from the 5th, 4 fields → `'A'`; a non-blank line that doesn't parse is still an entry (`plugin = ""`, `'A'`) so refusals never miss it; a missing file is empty; an unreadable existing file is `Err` (fail closed);
  - `pub fn ledger::of(entries: &[LedgerEntry], id: &str) -> Vec<&LedgerEntry>`: lines containing `=<id>|`;
  - migrate (port of `LEDGER_PY`): `pub fn ledger::merge(old: &str, cur: &str) -> (String /*new*/, Vec<String> /*conflicting old lines*/)` — new = cur (newline-terminated) + each old line whose key cur lacks; a conflict is an old key present in cur with a different trimmed line; `pub fn ledger::contains_all(old: &str, cur: &str) -> bool`.

- [ ] **Step 1: Write the failing tests.**
  - `propsline_set_rewrites_one_line_and_keeps_others_and_comments`, `propsline_set_appends_when_absent`, `propsline_drop_exact_key_only` (`bike` doesn't drop `bike-rack`), `propsline_comment_line_is_never_a_key`.
  - `toggles_drop_removes_only_that_off_line`, `toggles_off_ids_reads_off_true_lines`.
  - `plan_fresh_install_asks_everything`; `plan_update_asks_only_new_and_keeps_revocation`: old `[a,b]`, new `[a,b,c]`, current `{b}` → `ask=[c]`, `grant={b,c}`, `missing=[a]`; `plan_dropped_permission_is_removed_from_grant`.
  - `catalog_describe_marks_loud_and_unknown`.
  - `ledger_reads_v2_state_and_legacy_four_field_as_held`, `ledger_reads_old_base_ledger_too`, `ledger_of_matches_exact_id`, `ledger_unparsable_line_still_counts`, `ledger_read_unreadable_is_err` (a directory in place of the file).
  - `ledger_merge_keeps_both_and_reports_conflicts`, `ledger_contains_all`.
- [ ] **Step 2: Run them and confirm they fail.** Run `cargo test --test mibsdk_formats`. Expected: FAIL.
- [ ] **Step 3: Implement the modules.**
- [ ] **Step 4: Run the tests and confirm they pass.** Run `cargo test --test mibsdk_formats`. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(mibsdk): permission catalog, exact-key line editing for grants and toggles, approval plan, ledger v2`

### Task 6: Port the bundle-list generator (with `HMICar=`) to Rust — host-only

**Files:**
- Create: `src/mibsdk/bundles.rs`, `tests/mibsdk_bundles.rs`
- Create: `tests/fixtures/mibsdk/bundles_dsitracer_enabled.properties` (copy of the SDK repo's `installer/testdata/bundles_dsitracer_enabled.properties` on `feat/ui`)

**Interfaces:**
- Produces:
  - `pub fn generate(stock: &[u8], sdk: &SdkSpec, tracer_active: bool) -> Result<Vec<u8>>`: byte-for-byte semantics of `feat/ui`'s `installer/gen_bundles.py::generate`, with configurator, bundle line, bundle name (`sdk.bundle` before `=`) and `HMICar=` values from `SdkSpec`. It touches exactly the `serviceDelegateConfigurator` line, the `Bundle.Startup.Autostart` value, the one `HMICar=` line (exact bytes `HMICar=<hmicar_stock>` or `HMICar=<hmicar>` accepted, replaced by `HMICar=<hmicar>` keeping its EOL; anything else → "HMICar is not stock (<value>); refusing"; zero or several → "expected exactly one HMICar= line, found N"), and appends the bundle line if absent. Output is a fixed point.
  - `pub fn sdk_properties(sdk: &SdkSpec, tracer_active: bool) -> Vec<u8>`: `chain.configurator=<tracer_configurator>\n` when `tracer_active`, then each `sdk.properties` entry plus `\n`.
  - `pub fn check_hmicar(text: &[u8], what: &str, allowed: &[&str]) -> Result<()>` (port of `mibsdk-dev`'s `check_hmicar`): exactly one line matching `HMICar[ \t]*=` (after stripping one trailing `\r`), byte-equal to one of `allowed` full lines; else "HMICar is not stock in <what> (<line or 'no HMICar= line'>); refusing; nothing changed".

- [ ] **Step 1: Port the tests** listed for Task 6 in the test port map, keeping the names (without `test_`), each with `// was: …`. Add `sdk_properties_with_and_without_tracer` with exact bytes:
  ```
  chain.configurator=de.audi.tghu.dsitrace.TraceConfigurator
  hook.services=org.dsi.ifc.carparkingsystem.DSICarParkingSystem
  ui.enabled=true
  ```
  and `check_hmicar_accepts_listed_lines_only_and_tolerates_crlf`, `check_hmicar_refuses_two_lines_and_spaced_foreign_value`.
- [ ] **Step 2: Run them and confirm they fail.** Run `cargo test --test mibsdk_bundles`. Expected: FAIL.
- [ ] **Step 3: Implement `bundles.rs`.** Hand-written line scanner (no regex crate).
- [ ] **Step 4: Run the tests and confirm they pass.** Run `cargo test --test mibsdk_bundles`. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(mibsdk): bundles_dsitracer_enabled.properties generator incl. HMICar= (port of gen_bundles.py)`

### Task 7: The SDK runtime package (`[sdk]`): install, migrate, update, rollback, remove — host-only

**Files:**
- Create: `src/mibsdk/sdk_pkg.rs`, `src/mibsdk/zipnames.rs`, `tests/sdk_pkg.rs`
- Modify: `src/install.rs` at `install_package` (`:232-349`; checks before `let _rw`, deploy inside the `staged` closure after `extract_to`/rename), `remove` (`:433-469`), `rollback` (`:471-497`); `src/ctx.rs` (`pub force: bool`); `src/cli.rs` (`uninstall-self`, `:529`)

**Interfaces:**
- Consumes: `SdkSpec`, `Kind` (Task 3); `stamp::verify`, `put*`, `installed_sdk`, `hmi_version`, `reboot_flag`, constants (Task 4); `ledger::{read, merge, contains_all}`, `grants`, `propsline` (Task 5); `bundles::{generate, sdk_properties, check_hmicar}` (Task 6); `backups::{backup_once, restore, forget, has_backup, is_tombstone}` (Task 2).
- Produces:
  - `pub fn zipnames::list(path: &Path) -> Result<Vec<String>>`: reads the end-of-central-directory record (searching the last 64 KiB + 22 bytes) and returns every central-directory entry name; anything malformed is `Err`.
  - `pub fn sdk_pkg::check(ctx: &Ctx, m: &Manifest, pkg_dir: Option<&Path>) -> Result<()>`. No writes. Refuses, each message ending "; nothing changed", in this order:
    1. HMI not in `sdk.hmi`: "unsupported HMI '<v>' (supported: …)";
    2. a `lsd/jars` entry containing `carplay` (case-insensitive), or any `*.carplay-stock` file under `HMI_ROOT` (recursive `fs::read_dir`; a read error refuses: "could not verify the absence of carplay-rgi") — "carplay-rgi is installed (…); the SDK does not support it (spec §13)";
    3. a `mibsdk-spike-*.jar` in `JARS`: "spike jar <name> is installed; run spikes/hmi-ui/run.sh uninstall first";
    4. **owned classes** (port of `check_owned_classes`): for each `JARS` entry ending `.jar` and not starting `mibsdk-`: a symlink → "<path> is a symlink; the ownership check cannot follow it"; a name with a space → "<path> has a space in its name; the ownership check cannot handle it"; not a regular file → skipped; `zipnames::list` fails → "cannot list <path> (not a zip?)"; an entry starting with an `sdk.owned` prefix and ending `.class` → "<name> defines <entry>, which the SDK owns";
    5. **`HMICar=`** (before any backup exists): if an adoptable or existing stock backup exists, `check_hmicar(live, "the installed <BUNDLES>", [stock line, sdk line])` and `check_hmicar(stock, "the stock bundles file", [stock line])`; else `check_hmicar(live, "the stock bundles file", [stock line])`;
    6. `TRACER_JAR` missing: "<path> is missing; the SDK chains DSITracer's configurator and needs it";
    7. update/rollback only: an installed plugin package with `!(sdk.min_api <= plugin.api <= sdk.api)`: "<id> needs plugin api N; this SDK supports <min>..<api>".
  - `pub fn sdk_pkg::migrate_state(ctx: &Ctx) -> Result<Vec<String>>` (port of `migrate_state`/`migrate_ledger`; caller holds rw; returns notes). Data: each `BASE/data/<f>` (skipping `*.tmp`, and any `f` already in `PERSIST/data`) is copied to `PERSIST/data/<f>.migrate.tmp`, renamed, checked; then `BASE/data` is removed; stray `*.migrate.tmp` removed. Ledger: old = `BASE/ledger.properties`, else `BASE/ledger.properties.tmp`; `ledger::merge(old, cur)`; write the new ledger with `put` if it changed; conflicts → keep the old file and note "WARNING: kept <old>: these overrides conflict with <PERSIST ledger> and were not merged:" + lines (uninstall keeps refusing on them); else read back, require `contains_all`, then delete old and `.tmp`. Finally remove `BASE/cache`, `BASE/boot.state`, `BASE/boot.state.tmp`. Any failure errors with "… (nothing was lost; run the install again)".
  - `pub fn sdk_pkg::deploy(ctx: &Ctx, m: &Manifest, ver_dir: &Path, prev_dir: Option<&Path>) -> Result<()>`. Caller holds rw. **All-or-undo**: on error it re-deploys `prev_dir` when given; for a fresh install it restores `BUNDLES` and `MARKER` from the backups and deletes the jars it wrote; then returns the original error. Order:
    1. `stamp::verify(ver_dir/<stamp>, Unit, None, jars)`;
    2. adopt or back up (below);
    3. create `BASE/plugins` and `PERSIST`;
    4. `migrate_state`;
    5. `put_file` each jar to `JARS` (0o644);
    6. `put` `BASE/sdk.properties`, then `BASE/permissions.json`;
    7. create `MARKER` if absent (never touch an existing one);
    8. `put` `BUNDLES` last, generated from the **backup** (never from the live file), with the backup's mode;
    9. write the reboot flag.
  - **Adoption** (first mibpkg install on a unit set up by `mibsdk-dev`): if `!has_backup(BUNDLES)` and `BASE/stock/tracer.was` exists (it is written last, so its presence means complete; its content must be `true` or `false`, else refuse "stock/tracer.was is corrupt"): copy `BASE/stock/bundles_dsitracer_enabled.properties` into `backup_path(BUNDLES)` with the mode from `BASE/stock/bundles.mode` (`[0-7]{3}`; 777 if absent, noted); record `MARKER` as a tombstone when `tracer.was` is `false`, else back up the live marker; after a byte comparison, remove `BASE/stock`. Otherwise, if the live `BUNDLES` contains `mibsdk` and there is no backup, refuse ("<BUNDLES> already references mibsdk but no stock backup exists; restore the stock file by hand"). Otherwise `backup_once(BUNDLES)` and `backup_once(MARKER)`. The tracer is active when the `MARKER` backup isn't a tombstone.
  - `pub fn sdk_pkg::undeploy(ctx: &Ctx, purge: bool) -> Result<String>`. Caller holds rw. Refuses when:
    - any plugin package is installed: "remove these plugins first: …";
    - pushed jars (`BASE/plugins/*.jar` not owned by a package) exist and `!ctx.force`: "these plugins were pushed by mibsdk-dev: …; remove them first (mibpkg sdk remove <id>) or add --force";
    - `ledger::read` (both files) has entries and `!ctx.force` — I2 text, one item per line:
      ```
      refusing to uninstall: these overrides of vehicle state are still in force (key=plugin|scope|prior|active|A or R):
        <line>…
      let the SDK put the prior values back first: mibpkg sdk safe-mode on, then restart the HMI
      (safe mode loads no plugin and restores every override in the ledger; check /tmp/mibsdk.log), then run the removal again.
      after a later reinstall, leave safe mode with: mibpkg sdk safe-mode off
      to uninstall anyway and leave those settings overridden, add --force
      ```
      With `--force` the result instead begins `WARNING: --force: uninstalling while these overrides of vehicle state are still in force:`, the lines, then `WARNING: nothing will restore their prior values; the car keeps the overridden settings until you change them back by hand (format: key=plugin|scope|prior|active|A or R).`
    Then: restore `BUNDLES` first (backup bytes and mode), then `MARKER` (a tombstone deletes it; an existing marker is left alone); remove the three jars, `BASE/sdk.properties`, `BASE/permissions.json`; without `purge` keep `BASE/grants.properties`, `BASE/plugins/` and `PERSIST`, and `rmdir` `BASE` only if empty; with `purge` remove `BASE`, `PERSIST` and `RAM`; forget both backups; write the reboot flag. Note: "kept plugin data in /mnt/persist/mibsdk (remove it with: mibpkg remove mibsdk --purge)" or "removed …".
- `install_package`, `remove` and `rollback` dispatch on `manifest.kind()`. For `Kind::Sdk` the result gets "; reboot the unit (or restart the HMI) to start it" (or "to finish removing it"). `remove` reads `ctx.force` (the CLI flag arrives in Task 9; tests set the field).
- `uninstall-self` refuses while `mibsdk` or any plugin package is installed.

- [ ] **Step 1: Write the failing tests.** Fixture helper `sdk_unit(f)` creates `lsd/` with the stock bundles fixture (mode 0o777, stock `HMICar=` line), `dsitracer_activated`, `DSITracer.jar`, `jars/`, and `ifs/version_info.txt`; `sdk_src(dir, version)` writes the `[sdk]` manifest, three jars, a valid `mibsdk-unit.stamp` and `permissions.json`; `jar_with(entries)` writes a minimal zip. Name the tests in the port map for Task 7 (with `// was: …`), plus:
  - `sdk_fresh_install_writes_jars_props_bundles_last_and_flags_reboot`: jar bytes equal the sources; `BUNDLES == bundles::generate(stock, spec, true)` with mode 0o777 and the SDK `HMICar=` line; `sdk.properties` begins `chain.configurator=` and contains `ui.enabled=true`; reboot flag exists; message ends "reboot the unit (or restart the HMI) to start it".
  - `sdk_install_on_tracer_off_unit_creates_marker_and_restore_removes_it`.
  - `sdk_install_adopts_mibsdk_dev_stock_backup` (Review Focus 3): live `BUNDLES` generated (SDK `HMICar=`), `BASE/stock/{bundles_dsitracer_enabled.properties,bundles.mode,tracer.was}` = stock, `777`, `true`; afterwards the backup equals the stock fixture and `BASE/stock` is gone.
  - `sdk_install_migrates_old_layout_state` (Review Focus 3): `BASE/data/hello.properties`, `BASE/ledger.properties` with one v2 line, `BASE/cache`, `BASE/boot.state` → data and ledger line in `PERSIST`, old files gone.
  - `sdk_install_refuses_generated_bundles_without_backup`.
  - `sdk_install_refusals_change_nothing`: unsupported HMI, carplay jar, carplay-stock file, spike jar, owned class (`de/audi/tghu/car/hmi/Activator.class` in `jars/other.jar`), foreign `HMICar=`, missing tracer — one assert per case; tree compared before and after.
  - `sdk_install_refuses_unstamped_jars`, `sdk_install_refuses_plugin_kind_stamp`.
  - `sdk_deploy_failure_undoes_fresh_install` (`jars/mibsdk-adapter-hqc.jar` is a directory): `BUNDLES` equals stock, no SDK jar left, `current` not written.
  - `sdk_update_failure_redeploys_previous_version`.
  - `sdk_update_refused_while_plugin_needs_other_api` (plugin api 3 vs SDK 1..2), `sdk_update_accepts_api_1_plugin_on_api_2_sdk`.
  - `sdk_remove_refuses_with_plugins_installed`, `sdk_remove_refuses_with_pushed_plugins_unless_force` (Review Focus 5).
  - `sdk_remove_refuses_on_ledger_entries_unless_force`: asserts each I2 line above; `--force` result starts with the WARNING line.
  - `sdk_remove_restores_stock_bundles_byte_for_byte_and_mode_keeps_persist_grants_and_plugins_dir`.
  - `sdk_remove_purge_removes_base_persist_and_ram`.
  - `uninstall_self_refuses_while_mibsdk_installed`.
  - `zipnames_lists_entries_and_rejects_garbage`.
- [ ] **Step 2: Run them and confirm they fail.** Run `cargo test --test sdk_pkg`. Expected: FAIL.
- [ ] **Step 3: Implement `zipnames.rs`, `sdk_pkg.rs` and the `install.rs` dispatch.** Checks run before `let _rw`; deploy runs inside `staged`, so a failure removes the staged version dir as today.
- [ ] **Step 4: Run the whole suite.** Run `cargo test`. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(mibsdk): SDK runtime package: stamped jars, install guards, state migration, bundle list from backup, guarded uninstall`

### Task 8: Plugin packages (`[plugin]`): checks, approval, deploy, hot-load, remove — host-only

**Files:**
- Create: `src/mibsdk/plugin_pkg.rs`, `src/mibsdk/ack.rs`, `tests/plugin_pkg.rs`
- Modify: `src/install.rs` (dispatch as in Task 7; `update_all` turns `NeedsApproval` into "<name>: needs permission approval (…)"); `src/ctx.rs` (`pub approved: Option<Vec<String>>`); `src/supervisor.rs:652-678` (`seed_once`)

**Interfaces:**
- Produces, in `src/mibsdk/plugin_pkg.rs`:
  - `#[derive(Debug)] pub struct NeedsApproval { pub package: String, pub version: String, pub ask: Vec<String>, pub lines: Vec<String> }`; `Display` = "approve these permissions to install <pkg> <ver>: <lines joined by '; '>"; implements `std::error::Error`; callers use `downcast_ref::<NeedsApproval>()`.
  - `pub fn check(ctx: &Ctx, m: &Manifest, old: Option<&Manifest>) -> Result<Approval>`. No writes. Refuses when: the SDK isn't installed ("install the mibsdk package first"); `!(sdk.min_api <= plugin.api <= sdk.api)` ("<id> needs plugin api N; the installed SDK supports <min>..<api>"); the unit HMI isn't in `sdk.hmi` ("the SDK does not support this firmware (…), so no plugin would load"); a permission is unknown to the installed catalog. Otherwise `grants::plan(new, old, current)`; returns `Err(NeedsApproval)` unless `ask ⊆ ctx.approved.unwrap_or_default()`. A pushed jar for the same id (not a package) is not a refusal: `old = None`, so every permission is asked.
  - `pub fn deploy(ctx: &Ctx, m: &Manifest, ver_dir: &Path, approval: &Approval) -> Result<Vec<String> /*notes*/>`. Caller holds rw. `stamp::verify(<jar>.stamp, Plugin, Some(id), [jar])`; `put_file` to `BASE/plugins/<id>.jar` (0o644); `grants::set(id, &approval.grant)`. Notes: the unrestricted WARNING; "replaced the pushed <id>.jar" when it took over a dev push; "<id> stays blocked until granted: <missing>; grant with: mibpkg sdk grant <id> <declared,…>" when `grants::missing` is non-empty.
  - `pub fn activate(ctx: &Ctx, id: &str) -> String` = `activate_with(ctx, id, ack::ACK_TIMEOUT)`. `pub fn activate_with(ctx, id, timeout: Duration) -> String`: runs **after** `install_package` has dropped its rw guard; counts `reload requested for <id>` lines in `LOG`; takes `ctx.mounts.rw("/mnt/app")` only to `put` a fresh token (`<now_secs>-<pid>-<counter>\n`) to `BASE/plugins/<id>.reload`, drops it, then `ack::wait`. Note: "loaded: <STATE> <reason>"; "reload requested; the SDK did not confirm within 6 s (it loads at the next HMI start)"; or, when `!sdk_running`, "the SDK is not running; it loads at the next boot".
  - `pub fn ack::wait(layout, id: &str, before: usize, timeout: Duration) -> Option<(String, String)>`: polls `LOG` every 250 ms until the `reload requested for <id>` count exceeds `before`, then returns state and reason of the last `STATUS <id> <STATE> <reason>` line after that point; `DISABLED reloading` keeps waiting. `pub const ACK_TIMEOUT: Duration = Duration::from_secs(6)`.
  - `pub fn remove_id(ctx: &Ctx, id: &str, purge: bool) -> Result<String>` — the **one** removal path for packages (`install::remove` calls it after its own bookkeeping) and for pushed ids (`mibpkg sdk remove`), port of `cmd_remove` (M2):
    - Present if any of: `BASE/plugins/<id>.jar`, `PERSIST/data/<id>.properties`, a grants line keyed `<id>`, a `<id>.off` line, `BASE/plugins/<id>.reload`, or ledger lines with `=<id>|`; else "<id> is not installed". So a re-run after a partial removal finishes it.
    - Under rw: remove the jar and check it is gone; `grants::drop_line`; `toggles::drop` (**before** the marker); `put` a fresh reload token. Drop rw.
    - If `sdk_running`, `ack::wait`. Then poll `ledger::of(id)` every 2 s up to `MIBPKG_SDK_REMOVE_WAIT_S` seconds (env, digits only, default 30): first read empty → "<id> has no overrides in the ledger"; drained later → "overrides of <id> restored"; timeout → "WARNING: these overrides of <id> are not restored yet:" + lines + "they are restored when the SDK next starts (the plugin is no longer installed); check /tmp/mibsdk.log".
    - `purge`: delete `PERSIST/data/<id>.properties` and `.tmp` only when the ack came back or the SDK isn't running, then "cleared the data of <id> (…)" and "Restart the HMI now: a running <id> may write its data back, and the storage cap frees the space only from the next runtime start."; otherwise keep them: "data kept: the SDK did not confirm the unload; run `mibpkg sdk data-clear <id>` after a restart".
    - Message head: "removed <id>: plugins/<id>.jar and its grants line[ and its <id>.off switch]; reload requested".
- **Rollback**: deploy the previous version's jar with `grant = current ∩ declared_prev`; never asks; then activates.
- **Seeding** (`seed_once`): skips `Kind::Sdk` and `Kind::Plugin` entries with "mibpkg: seed <name>: skipped: SDK and plugin packages are installed by hand (they change the HMI bundle list / need permission approval)"; plain packages install as today. **[my call]** (avoids alphabetical order installing a plugin before `mibsdk`, and a seed card editing the bundle list unasked; the `seeded` marker is still written).
- `install_file` and `install` both go through `install_package`.

- [ ] **Step 1: Write the failing tests.** Helpers `with_sdk(f)` (Task 7's SDK installed), `plugin_src(dir, id, ver, api, perms)` (jar + valid `<id>.jar.stamp`), `pushed(f, id)` (jar in `BASE/plugins` without a package). Fake the runtime's ack by appending to `tmp/mibsdk.log` from a thread spawned before the call. Port the Task 8 names in the test port map (`// was: …`; they drive `remove_id` on a pushed id), plus:
  - `plugin_install_without_approval_returns_needs_approval_and_changes_nothing` (`ask == ["vehicle.read"]`).
  - `plugin_install_with_approval_deploys_jar_writes_grants_and_marker` ("loaded: ENABLED").
  - `plugin_install_without_permissions_needs_no_approval`.
  - `plugin_update_asks_only_new_permissions_and_keeps_revocations` (Review Focus 2): old `[vehicle.read, ui.slot.carmenu]`, new adds `network`, grants line `hello=ui.slot.carmenu` → `ask == ["network"]`; with approval the line is `ui.slot.carmenu,network`; notes contain "stays blocked until granted: vehicle.read".
  - `plugin_install_accepts_api_1_and_2_on_sdk_1_to_2`, `plugin_install_refused_on_api_out_of_range` (api 3), `plugin_install_refused_without_sdk`, `plugin_install_refused_on_unsupported_hmi`, `plugin_install_refused_on_permission_unknown_to_sdk`.
  - `plugin_install_refused_when_stamp_id_differs`, `plugin_install_warns_for_unrestricted`.
  - `plugin_install_takes_over_a_pushed_jar` (Review Focus 5).
  - `plugin_install_when_sdk_not_running_says_next_boot`; `plugin_install_without_ack_says_not_confirmed` (`activate_with(.., 300 ms)`).
  - `plugin_activate_holds_no_rw_guard_while_waiting` (`RecordingSys` calls end with `ro /mnt/app` before the ack thread writes).
  - `plugin_remove_keeps_data_by_default_and_drops_grants_and_off_lines`.
  - `plugin_purge_waits_for_unload_before_deleting_data` (Review Focus 4): no ack → kept with the note; ack → gone.
  - `sdk_remove_handles_a_pushed_plugin` (Review Focus 5), `remove_id_ledger_drains` and `remove_id_ledger_does_not_drain_warns` (`MIBPKG_SDK_REMOVE_WAIT_S=1`).
  - `plugin_rollback_never_adds_grants`.
  - `update_all_reports_needs_approval_and_updates_the_rest`.
  - `seed_skips_sdk_and_plugin_packages_and_installs_plain_ones`.
- [ ] **Step 2: Run them and confirm they fail.** Run `cargo test --test plugin_pkg`. Expected: FAIL.
- [ ] **Step 3: Implement `plugin_pkg.rs`, `ack.rs`, the dispatch and the seed rule.**
- [ ] **Step 4: Run the whole suite.** Run `cargo test`. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(mibsdk): plugin packages with permission approval, hot-load via reload marker, one removal path for packages and pushed plugins`

### Task 9: CLI — approval, force, install-file, and the `sdk` verbs — host-only

**Files:**
- Modify: `src/cli.rs` (`USAGE`, arg parsing at `:63-69`, the action arm at `:160-167`, `action()` at `:346`)
- Test: `tests/cli_sdk.rs`

**Interfaces:**
- Produces:
  - **Global action flags** after `install | install-file | update-all | remove | gem install | gem install-file | gem remove`: `--approve=<perm,…>` (or `--approve=` for none), `--force`, and `--purge` with alias `--purge-data`. They set `ctx.approved`, `ctx.force`, purge.
  - **`install-file <pkg> <upload-id> [collection|-]`** joins the top-level lock arm (exit code reflects failure); default collection `-`. `<upload-id>` must pass `web::settings::is_id` (32 hex).
  - **Exit code 3** when the error downcasts to `NeedsApproval`: stdout gets one line per `lines` entry, then `approve with: --approve=<ask joined by ','>`. In the `gem` arm the result stays `FAILED: <Display>`.
  - **`mibpkg sdk <verb>`**, under the action lock; each verb except `status` refuses "mibsdk is not installed (run: mibpkg install mibsdk, or mibsdk-dev install)" when `installed_sdk` is `None` (`need_sdk`):
    - `status`: `sdk: <version|not installed>`, `api: <min>..<api>`, `hmi: <unit hmi> (supported|UNSUPPORTED)`, `running: yes|no`, `reboot needed: yes|no`, the live `HMICar=` line of `BUNDLES` (`HMICar=<missing>` when absent; a warning when `BUNDLES` is unreadable), one `plugin <id> <version|pushed> grants=<…>[ off][ blocked: <missing>]` per package or pushed jar, then `ledger: <n> override(s)` and the lines. Spec §6.1: a non-empty ledger adds `WARNING: vehicle overrides are in force; disable their plugins before a firmware update (a firmware without a matching adapter cannot restore them)`; with an UNSUPPORTED HMI also `WARNING: the SDK is off on this firmware and cannot restore these overrides`. Exit 0 whenever mibpkg ≥ 0.8.0 runs it.
    - `grant <id> <perm,…>`: id via `pkg_arg`, list `^[A-Za-z0-9._,-]*$` (may be empty = revoke all); `grants::set` under rw, then `plugin_pkg::activate`. Message "granted <id>: <perms|<none>>" plus the activate note (a revoke typically yields "loaded: BLOCKED_PERMISSIONS missing permissions: …").
    - `push <id>`: dev only. Jar `layout.run("push.<id>.jar")`, stamp `push.<id>.jar.stamp`; `stamp::verify(Plugin, Some(id))` (prints the unrestricted WARNING); refuses when `id` is a package ("<id> is installed as a package; remove it first or install a newer version"); deploys with the existing grants only; fresh token; activates; deletes both `/tmp` files on every exit path.
    - `remove <id> [--purge|--purge-data]`: a package → the `install::remove` path; otherwise `plugin_pkg::remove_id`.
    - `safe-mode on|off`: `on` `put`s `BASE/SAFE_MODE` under rw ("safe mode marker set; it takes effect on the next HMI restart"); `off` removes it and writes `PERSIST/boot.state` = `BOOT_STATE_RESET`, with `mibsdk-dev`'s four-line explanation (restart promptly so the reset sticks).
    - `boot-state set-wrap <n>`: `n` matches `^[0-9]{1,6}$` else usage exit 2; rewrites `PERSIST/boot.state` replacing the `wrapBoots=` line (CR stripped) or appending it, every other line kept; a missing file starts from `BOOT_STATE_RESET`; no rw. Message "boot.state: wrapBoots=<n> (other keys kept); it is read on the next HMI start".
    - `data-clear <id>`: removes `PERSIST/data/<id>.properties{,.tmp}` ("no data for <id> …; nothing to clear" when neither exists) and prints the restart advice.
  - `mibpkg sdk` without a verb prints usage and exits 2.

- [ ] **Step 1: Write the failing tests** (`--root <tmp>` against the binary, following `tests/cli_verbs.rs`; port the Task 9 names in the test port map with `// was: …`):
  - `install_file_needing_approval_exits_3_and_prints_approve_hint`, `install_file_with_approve_installs`, `install_file_rejects_short_upload_id`.
  - `remove_mibsdk_force_flag_reaches_undeploy`, `remove_accepts_purge_data_alias`.
  - `sdk_status_lists_plugins_pushed_off_blocked_grants_ledger_and_hmicar`, `sdk_status_warns_about_ledger_before_firmware_update`.
  - `sdk_grant_rewrites_line_and_writes_marker`, `sdk_grant_empty_list_revokes_all`.
  - `sdk_push_refuses_unstamped_and_package_owned_ids_and_cleans_tmp`, `sdk_push_refuses_without_sdk`, `sdk_push_keeps_existing_grants_and_writes_fresh_token`.
  - `sdk_remove_pushed_id_and_package_id`.
  - `sdk_safe_mode_on_off`, `sdk_boot_state_set_wrap`, `sdk_data_clear`, `sdk_without_verb_exits_2`.
- [ ] **Step 2: Run them and confirm they fail.** Run `cargo test --test cli_sdk`. Expected: FAIL.
- [ ] **Step 3: Implement the flags and verbs in `src/cli.rs`.**
- [ ] **Step 4: Run the whole suite.** Run `cargo test`. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(cli): --approve/--force, install-file, mibpkg sdk status|grant|push|remove|safe-mode|boot-state|data-clear`

### Task 10: Web — permissions in plain words, approve before install, revoke — host-only

**Files:**
- Modify:
  - `src/catalog.rs`: `Avail` and `Inst` gain `kind: Option<String>` and `permissions: Vec<…>` (described); the installed side adds `granted` from `grants::read` and `blocked: Vec<String>` from `grants::missing`;
  - `src/web/upload.rs:141-157`: `Inspection` gains `kind`, `permissions`, `needs_approval: Vec<String>` (`grants::plan` against the installed version);
  - `src/web/actions.rs:12-23`: `Install` gains `#[serde(default)] approve: Vec<String>` (each validated against the permission pattern) appended as `--approve=…`; new action `Grant { id: String, permissions: Vec<String> }` → `mibpkg sdk grant <id> <a,b>`;
  - `src/web/routes.rs:~375`: the upload install takes `approve` the same way;
  - `web/js/views/package.js`, `web/js/views/upload.js`.
- Create: `web/js/permissions.js`, `web/tests/permissions.test.mjs`
- Test: `tests/web_actions.rs`, `tests/web_upload.rs` (extend)

**Interfaces:**
- JSON: `permissions: [{id, text, loud, granted?}]` on package views and the inspection; `blocked: [id]` on installed plugins; `needs_approval: [id]` on the inspection.
- JS:
  - `export function approvalSheet(perms /*[{id,text,loud}]*/, onApprove /*(ids)=>void*/)`: one row per permission id (`data-perm=<id>`, so an "optional" checkbox can be added later), `loud` rows get class `warn` and the prefix "Warning:", an "Allow and install" button calls `onApprove(ids)`; Cancel sends nothing. Install/Update of plugin packages opens it when the newly asked set isn't empty.
  - `export function grantsEditor(perms /*[{id,text,loud,granted}]*/, onSave /*(ids)=>void*/)`: a checkbox per declared permission (checked = granted) and Save; when any declared permission is unchecked it shows "This plugin stops running until every permission it asks for is granted." (all-or-nothing enforcement; see Global Constraints follow-up). Shown on an installed plugin package's view.

- [ ] **Step 1: Write the failing tests.**
  - `install_action_with_approve_builds_argv_with_flag`; `install_action_rejects_bad_permission_strings` (`"a b"`, `"--x"`).
  - `grant_action_builds_sdk_grant_argv`, `grant_action_rejects_bad_id_and_permission`.
  - `upload_inspection_lists_needs_approval_for_new_permissions_only`.
  - JS: `approvalSheet lists plain words, marks loud, passes ids`; `grantsEditor warns when a declared permission is unchecked and passes the checked ids`.
- [ ] **Step 2: Run them and confirm they fail.** Run `cargo test --test web_actions --test web_upload && node --test web/tests/*.test.mjs`. Expected: FAIL.
- [ ] **Step 3: Implement the API fields, the actions and the JS.**
- [ ] **Step 4: Run the tests and confirm they pass.** Same commands. Expected: PASS. Then check by eye with `scripts/web-fixture.sh <dir>` and `MIBPKG_WEB_DIR=web`: upload a plugin `.mpk` (sheet appears), then uncheck a permission on its package view (warning appears).
- [ ] **Step 5: Commit.** `feat(web): plugin permissions in plain words; approve before install/update; revoke grants`

### Task 11: mibpkg 0.8.0 — version, docs, cross-build — host-only

**Files:**
- Modify: `Cargo.toml` (`version = "0.8.0"`), `README.md` ("Plugin SDK packages": kinds, approval/exit 3, `sdk` verbs, the id-length gap, 0.7 refusing `[plugin]` manifests, revocation = plugin blocked until re-granted, optional permissions as a follow-up), `docs/unit-facts.md` (rows for `/ifs/version_info.txt`, SDK paths, `plugins.properties`, ledger v2, the rw holder protocol), `docs/ideas.md` (`[[patch]]` note: the SDK uses `[sdk]` instead)

**Interfaces:**
- Produces the cross-built `out/…/mibpkg` (`scripts/build.sh`) and `mibpkg-0.8.0.mpk` (`mibpkg build packaging/mibpkg`), and the documented **rw holder protocol for other tools**: "take `/tmp/mibpkg.rwlock` (`create_new`, pid inside), create `/tmp/mibpkg.rw.app.<pid>`, `mount -uw`; on release remove your file and `mount -ur` only if no live `mibpkg.rw.app.*` remains. Re-assert `mount -uw` before each write and verify by read-back: bench-e2e F8 showed other processes remount `/mnt/app` read-only at any time, and a 0.7 supervisor still running during an upgrade does not honour holders."

- [ ] **Step 1: Bump the version and write the docs.**
- [ ] **Step 2: Run the full suite.** Run `cargo test && node --test web/tests/*.test.mjs`. Expected: PASS.
- [ ] **Step 3: Cross-build.** Run `scripts/build.sh`. Expected: exits 0 and prints the armv7 binary path. Run `target/release/mibpkg build packaging/mibpkg <out>`; expected `mibpkg-0.8.0.mpk`.
- [ ] **Step 4: Commit.** `chore: bump to 0.8.0 (mibsdk plugin and SDK packages, cross-process rw holders)`

### Task 12: SDK repo — permission catalog, plugin ids and versions mibpkg can package — host-only

**Files:**
- Create: `api/permissions.json`, `api/src/test/java/mibsdk/api/PermissionCatalogTest.java` (add JUnit `testImplementation` to `api/build.gradle.kts` if the convention doesn't give it)
- Create: `build-logic/src/test/java/mibsdk/build/PluginJsonTest.java` (or extend the existing one)
- Modify: `build-logic/src/main/java/mibsdk/build/PluginJson.java`, its caller `MibPluginPlugin.java`; `plugins/hello/…/plugin.json`, `plugins/crashy/…/plugin.json` (`"version": "1.0.0"`)

**Interfaces:**
- Produces:
  - `api/permissions.json`, exactly (texts **[my call]**; `network` is not loud **[my call]**: it is visible in the plain words and the bytecode scan, and loud stays reserved for "the SDK cannot contain it"):
    ```json
    {"format":1,"api":2,"min_api":1,"permissions":[
     {"id":"vehicle.read","group":"Vehicle","text":"Read vehicle state: ignition, gear, parking sensors","loud":false},
     {"id":"vehicle.parking.write","group":"Vehicle","text":"Change parking-sensor settings, for example mute the rear tones","loud":false},
     {"id":"hooks.parking","group":"Hooks","text":"Watch and override parking-system messages","loud":false},
     {"id":"ui.slot.carmenu","group":"Screen","text":"Add a row to the Car menu","loud":false},
     {"id":"ui.slot.statusbar","group":"Screen","text":"Show an item in the status bar","loud":false},
     {"id":"network","group":"Network","text":"Open network connections (local network or internet)","loud":false},
     {"id":"platform.unrestricted","group":"Danger","text":"Unrestricted: may use reflection and stock HMI classes; the SDK cannot contain it","loud":true}]}
    ```
  - `PluginJson` gains `final String name; final String version; final int api;` and package-private `static final int MIN_API = 1, MAX_API = 2` (already mirrored; now visible to `MpkStage`). `read` additionally fails when:
    - the id isn't `[a-z0-9][a-z0-9-]{1,39}`: "… (mibpkg package names are at most 40 characters)";
    - the id is `mibsdk`: "… is reserved for the SDK package";
    - `version` isn't semver (`\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?`): "\"version\" must be semver like 1.0.0 (mibpkg compares versions)".
    The api range check (1..2) is unchanged.

- [ ] **Step 1: Write the failing tests.**
  - `PermissionCatalogTest.everyPermissionsConstantHasExactlyOneEntryAndViceVersa` (reflect over `public static final String` fields of `mibsdk.api.Permissions`; 7 entries).
  - `PermissionCatalogTest.textIsNonEmptyAndOnlyUnrestrictedIsLoud`.
  - `PermissionCatalogTest.apiRangeMatchesSdkInfo`: `api == SdkInfo.API_VERSION`, `min_api == SdkInfo.MIN_API_VERSION`.
  - `PluginJsonTest.rejectsIdAndVersionMibpkgCannotPackage`: a 41-character id fails, `"1.0"` fails, `"mibsdk"` fails, `hello`/`"1.0.0"`/api 1 and api 2 pass.
  - `PluginJsonTest.exposesNameVersionApi`.
- [ ] **Step 2: Run them and confirm they fail.** Run `./gradlew :api:test && ./gradlew -p build-logic test`. Expected: FAIL.
- [ ] **Step 3: Implement the catalog, the `PluginJson` fields and checks, update `MibPluginPlugin`'s use of `PluginJson`, and bump `hello` and `crashy` to `1.0.0`.**
- [ ] **Step 4: Run the tests and build every plugin.** Run `./gradlew -p build-logic test && ./gradlew :api:test :plugins:hello:mibPluginJar :plugins:crashy:mibPluginJar :plugins:bike-rack:mibPluginJar :plugins:hello-menu:mibPluginJar :plugins:daemon-status:mibPluginJar`. Expected: PASS and BUILD SUCCESSFUL.
- [ ] **Step 5: Commit.** `feat(build): permission catalog (7 permissions, api 1..2); plugin ids and versions that mibpkg can package`

### Task 13: SDK repo — build `.mpk` packages for plugins and the SDK — host-only

**Files:**
- Create: `build-logic/src/main/java/mibsdk/build/{MpkStage,MibpkgBuildTask}.java`, `build-logic/src/test/java/mibsdk/build/MpkStageTest.java`
- Modify: `MibPluginPlugin.java` (register `mibPackage`, `dependsOn(mibPluginJar)` — the stamp is its output); `Toolchain.java` (`mibpkg(Project)` reads Gradle property `mibsdk.mibpkg`, default `mibpkg` on PATH); `build-logic/build.gradle.kts` (test task `systemProperty("mibsdk.mibpkg", findProperty("mibsdk.mibpkg") ?: "")`); `dist/build.gradle.kts` (register `mibpkgPackage`, `dependsOn` the task writing `mibsdk-unit.stamp`); `gradle.properties` (`mibsdk.version=0.1.0`, comment for `mibsdk.mibpkg`)

**Interfaces:**
- Consumes: the D1 stamps as written on `feat/ui`; `PluginJson.{id,name,version,api,permissions,MIN_API,MAX_API}` (Task 12); `api/permissions.json`.
- Produces:
  - `MpkStage.pluginManifest(PluginJson p) -> String`, exactly:
    ```toml
    [package]
    name = "<id>"
    version = "<version>"
    description = "<name> (mibsdk plugin)"

    [compat]
    units = ["MHI2Q"]

    [plugin]
    jar = "<id>.jar"
    api = <p.api>
    permissions = [<"p", …>]
    ```
  - `MpkStage.sdkManifest(String version) -> String`: `[package] name="mibsdk"`, `[compat] units=["MHI2Q"]`, and `[sdk]` with `api = 2`, `min_api = 1` (from `PluginJson.MAX_API`/`MIN_API`), `hmi = ["HMI_AU_MIB2_HQC_NAR_G22_C10_180803"]`, `jars = ["mibsdk-runtime.jar","mibsdk-adapter-hqc.jar","mibsdk-rt-retrotranslator.jar"]`, `stamp = "mibsdk-unit.stamp"`, `configurator`, `bundle`, `tracer_configurator`, `hmicar_stock = "de.audi.tghu.car.hmi.Activator"`, `hmicar = "mibsdk.adapter.hqc.ui.SdkCarActivator"`, `owned = ["de/audi/tghu/car/hmi/","de/audi/atip/model/CarModelBank"]`, `properties = ["hook.services=org.dsi.ifc.carparkingsystem.DSICarParkingSystem","ui.enabled=true"]`, `permissions = "permissions.json"` (Global Constraints values; they move here from `gen_bundles.py` and `mibsdk-dev`). TOML strings escaped.
  - `mibPackage` (plugins): stage `build/mibpkg-stage/` = `manifest.toml` + `<id>.jar` + `<id>.jar.stamp` (from `build/mibsdk/`), run `<mibpkg> build <stage> build/mibsdk/` → `build/mibsdk/<id>-<version>.mpk`.
  - `:dist:mibpkgPackage`: stage the three jars, `mibsdk-unit.stamp` as is, `permissions.json` and `manifest.toml`; deletes older `build/dist/mibsdk-*.mpk` first; output `dist/build/dist/mibsdk-<version>.mpk`.
  - `MibpkgBuildTask` (cacheable; inputs: the stage dir and the binary path) fails with "host mibpkg not found: build it (cargo build --release in the mibpkg repo) and pass -Pmibsdk.mibpkg=<path>" when the binary is missing.

- [ ] **Step 1: Write the failing tests.**
  - `MpkStageTest.pluginManifestIsExactAndEscaped` (a name with `"`; api 2 plugin renders `api = 2`).
  - `MpkStageTest.sdkManifestCarriesTheValuesGenBundlesAndMibsdkDevUsed` (each value above).
  - `MpkStageTest.sdkApiRangeMatchesThePermissionCatalog` (reads `../api/permissions.json`).
  - `MibPluginPluginFunctionalTest.mibPackageBuildsMpk`, guarded by `assumeTrue(new File(System.getProperty("mibsdk.mibpkg", "")).canExecute())`: the `.mpk` exists and `tar -tzf` lists `manifest.toml`, `hello.jar`, `hello.jar.stamp`.
- [ ] **Step 2: Run them and confirm they fail.** Run `./gradlew -p build-logic test`. Expected: FAIL.
- [ ] **Step 3: Implement `MpkStage`, `MibpkgBuildTask` and the task registration.**
- [ ] **Step 4: Build both kinds of package (needs a host mibpkg with Tasks 3–4).** Run `(cd <mibpkg worktree> && cargo build --release)`, then `./gradlew -p build-logic test -Pmibsdk.mibpkg=<mibpkg worktree>/target/release/mibpkg && ./gradlew -Pmibsdk.mibpkg=<same> :dist:mibpkgPackage :plugins:hello:mibPackage :plugins:crashy:mibPackage :plugins:bike-rack:mibPackage :plugins:hello-menu:mibPackage :plugins:daemon-status:mibPackage`. Expected: PASS (functional test not skipped); exactly one `dist/build/dist/mibsdk-0.1.0.mpk`; `mibpkg build` accepted every manifest (it now checks the jars, stamps and catalog exist).
- [ ] **Step 5: Commit.** `feat(build): mibPackage and :dist:mibpkgPackage build mibpkg .mpk files`

### Task 14: `mibsdk-dev` delegates to mibpkg; delete the duplicated install logic — host-only

**Files:**
- Modify: `installer/mibsdk-dev`, `installer/test_mibsdk_dev.py`
- Delete: `installer/gen_bundles.py`, `installer/test_gen_bundles.py`; `installer/testdata/` stays only if a remaining test uses it.
- Modify docs:
  - spec §5.3: add `permissions.json` to the install row; replace "`mibsdk-dev` remounts" with "mibpkg owns `/mnt/app` writes";
  - spec §7: revocation is the mibpkg web grants editor and `mibpkg sdk grant`; enforcement is all-or-nothing today (a revoke blocks the plugin); optional permissions are a follow-up;
  - spec §10: an "implemented by Plan 3" note naming `[plugin]` and `[sdk]`;
  - `docs/car-readiness.md`: keep its gate wording (M7, I2, M2) and change only the follow-up lines: "`mibsdk-dev remove <id>`" → `mibpkg sdk remove <id>`; the read-only-remount item → mibpkg holders + `put`; M7 now enforced on the unit by mibpkg;
  - `docs/superpowers/plans/2026-10-20-mibsdk-ui.md` decisions: "revocation is Plan 3 / mibpkg" → points at Task 10.

**Interfaces:**
- Consumes: the Task 9 verbs; the Task 13 `.mpk` outputs.
- Produces the `mibsdk-dev` commands (no remote `mount` calls left; every upload goes to `/tmp` only):

  | Command | What it does on the unit |
  |---------|--------------------------|
  | `install` | Upload the single `$DIST/mibsdk-*.mpk` (or `$MIBSDK_MPK`) to `/tmp/mibpkg.upload.<uid>.mpk`, then `/tmp/mibpkg install-file mibsdk <uid> -`. 0 or >1 matches: die "found N mibsdk-*.mpk in $DIST; build with ./gradlew :dist:mibpkgPackage or set MIBSDK_MPK" |
  | `uninstall [--purge] [--force]` | `/tmp/mibpkg remove mibsdk [--purge] [--force]` |
  | `install-plugin <file.mpk> [--approve=…]` | Upload, then `install-file <id> <uid> - --approve=…`; exit 3 passed through with mibpkg's lines |
  | `remove <id> [--purge-data\|--purge]` | `/tmp/mibpkg sdk remove <id> [--purge]` (packages and pushed ids) |
  | `push <plugin.jar>` | Snapshot jar + `<jar>.stamp` into `$TMP` (refuse without the stamp; nothing uploaded); id from the snapshot's `plugin.json`; upload both to `/tmp/mibpkg.push.<id>.jar{,.stamp}`; `/tmp/mibpkg sdk push <id>` |
  | `grant <id> <perms>` | `/tmp/mibpkg sdk grant …` |
  | `safe-mode on\|off` | `/tmp/mibpkg sdk safe-mode …` |
  | `boot-state set-wrap <n>` | `/tmp/mibpkg sdk boot-state set-wrap <n>` |
  | `data-clear <id>` | `/tmp/mibpkg sdk data-clear …` |
  | `status` | `/tmp/mibpkg sdk status` (includes the `HMICar=` line), then the `STATUS` lines of the log |
  | `log`, `restart` | Unchanged |

  - `<uid>` = `od -An -tx1 -N16 /dev/urandom | tr -d ' \n'` on the Mac (32 hex; passes `is_id`).
  - Upload = `ssh … "cat > /tmp/<name>"`, read back with `cat` and `cmp`; on mismatch remove it and die. Host-side stamp checking is dropped; the unit's `stamp::verify` is authoritative **[my call]**.
  - Only delegating commands probe first with `ssh … /tmp/mibpkg sdk status >/dev/null`; non-zero dies "mibpkg on <host> is missing or older than 0.8.0; update it (see the mibpkg README)". `log` and `restart` don't probe.
  - The `.mpk` id for `install-plugin` comes from `tar -xzOf <mpk> manifest.toml` on the Mac.
  - `spikes/hmi-ui/run.sh` copies `mibsdk-dev`'s conventions instead of sourcing it, so deleting `push_file`/`rw_*` doesn't break it.

- [ ] **Step 1: Rewrite the tests.** `installer/test_mibsdk_dev.py` keeps the fake `ssh`; a fake `/tmp/mibpkg` under `$FAKE_UNIT` appends argv to `$FAKE_UNIT/mibpkg.argv` and exits `$FAKE_MIBPKG_RC`. Tests:
  - `test_install_uploads_the_single_mpk_and_calls_install_file` (bytes equal; argv `install-file mibsdk <uid> -`; `<uid>` matches `^[0-9a-f]{32}$`).
  - `test_install_refuses_zero_or_two_mpks`, `test_install_uses_MIBSDK_MPK`.
  - `test_old_or_missing_mibpkg_is_refused_before_any_upload`, `test_log_and_restart_do_not_probe`.
  - `test_install_plugin_passes_approve_and_exit_3_through`.
  - `test_push_requires_stamp_beside_jar`, `test_push_uploads_jar_and_stamp_and_calls_sdk_push`, `test_pushes_the_bytes_it_verified_not_a_jar_rewritten_afterwards` (kept name: the uploaded bytes are the snapshot's).
  - `test_remove_maps_to_sdk_remove_with_purge_data_alias`, `test_uninstall_flags_pass_through`.
  - `test_grant_safe_mode_boot_state_data_clear_map_to_sdk_verbs`.
  - `test_script_has_no_remote_mount_calls` (`"mount -u"` not in the script text).
- [ ] **Step 2: Run them and confirm they fail.** Run `python3 -m unittest installer/test_mibsdk_dev.py -v`. Expected: FAIL.
- [ ] **Step 3: Rewrite `mibsdk-dev`.** Keep `usage`, `die`, `info`, `SSH_OPTS`, `set_host`, `check_id`, `make_tmp`/`cleanup` and the host-argument arity rule. Delete `push_file`, `push_failed`, `sweep_push_tmp`, `rw_*`, the `RW` branch of `rsh`, `migrate_*`, `STAMP_PY`, `LEDGER_PY`, `GRANTS_PY`, `check_ledger`, `check_hmicar`, `check_no_spike_jar`, `check_owned_classes`, `restore_bundles_mode`, `perm_octal`, `remote_mode`, `need_sdk` and the constants they alone use. Delete `gen_bundles.py` and its test. Update the docs listed above.
- [ ] **Step 4: Run the tests and the port-map check.** Run `python3 -m unittest installer/test_mibsdk_dev.py -v && ./gradlew check`, then for every name in this plan's test port map (except the two kept in Python) `grep -rq "was: <name>" <mibpkg worktree>/tests || echo MISSING <name>`. Expected: PASS and no `MISSING` line.
- [ ] **Step 5: Commit.** `refactor(installer): mibsdk-dev delegates every unit write to mibpkg; drop gen_bundles.py (ported to mibpkg)`

### Task 15: Bench deploy — mibpkg 0.8.0 and adopting the SDK (10.20.0.23) — bench

**Files:**
- Modify: `docs/bench-e2e.md`: add a "Plan 3: mibpkg distribution" section (steps, outputs, screenshots under `docs/bench-e2e/`, F-number table).

**Interfaces:**
- Consumes: mibpkg 0.8.0 (Task 11); `mibsdk-0.1.0.mpk` and the plugin `.mpk` files (Task 13); the new `mibsdk-dev` (Task 14).
- Produces: a bench whose SDK is the `mibsdk` package, for Task 16.

`SSH` below = `ssh -o HostKeyAlgorithms=ssh-rsa -o PubkeyAcceptedKeyTypes=ssh-rsa root@10.20.0.23`. Screen: keypanel (`curl -s 10.20.0.23:8099/status`, then poll `/screen.jpg` twice). An HMI restart takes 4–5 minutes; if the unit gets no lease, `ssh rpi-bench keypanel up`.

- [ ] **Step 1: Baseline.**
  - `SSH /tmp/mibpkg status` (note the version), `SSH ls -l /mnt/app/eso/hmi/lsd /mnt/app/eso/hmi/lsd/jars /mnt/app/eso/hmi/lsd/mibsdk /mnt/app/eso/hmi/lsd/mibsdk/stock /mnt/app/eso/hmi/lsd/mibsdk/plugins`, `SSH cat /mnt/persist/mibsdk/ledger.properties /mnt/persist/mibsdk/plugins.properties`.
  - Save local copies of `mibsdk/stock/bundles_dsitracer_enabled.properties` (stock reference) and the live bundles file.
  - Expected: SDK installed by the old `mibsdk-dev` (`stock/tracer.was` = `true`); live `HMICar=mibsdk.adapter.hqc.ui.SdkCarActivator`; stock `HMICar=de.audi.tghu.car.hmi.Activator`; no spike jar; record which plugins are pushed and any ledger lines.
- [ ] **Step 2: Update mibpkg to 0.8.0 and let the supervisor restart.**
  - Upload `mibpkg-0.8.0.mpk` to `/tmp/mibpkg.upload.<uid>.mpk` (`<uid>` 32 hex) and run `SSH /tmp/mibpkg install-file mibpkg <uid> -`.
  - Wait until the 0.7 supervisor is gone and 0.8.0 supervises (`SSH /tmp/mibpkg status` reports 0.8.0) **before Step 3**: a 0.7 supervisor doesn't honour holders.
  - `SSH /tmp/mibpkg sdk status`. Expected: exit 0; `sdk: not installed`; `running: yes`; the `HMICar=` SDK line; the pushed plugins listed as `pushed`.
- [ ] **Step 3: Adopt the SDK as a package.**
  - `installer/mibsdk-dev install`. Expected: "installed mibsdk 0.1.0; reboot the unit (or restart the HMI) to start it"; `mibsdk/stock/` gone.
  - The mibpkg backup `SSH cat '/mnt/app/root/mibpkg/state/backups/mnt%app%eso%hmi%lsd%bundles_dsitracer_enabled.properties'` equals the Step 1 stock copy; the live bundles file equals the Step 1 live copy (same generator, same inputs).
  - `mibsdk-dev restart`; after 4–5 minutes `mibsdk-dev status`. Expected: `boot healthy`, the pushed plugins `ENABLED` again, keypanel `bridge_connected:true`, the Car menu shows the SDK rows.
- [ ] **Step 4: Commit.** `docs(e2e): Plan 3 deploy: mibpkg 0.8.0 adopts the mibsdk-dev SDK install`

### Task 16: Bench end-to-end — approval, F8, web, remove, uninstall (10.20.0.23) — bench

**Files:**
- Modify: `docs/bench-e2e.md` (the Plan 3 section).

**Interfaces:**
- Consumes: Task 15's bench state; `hello-1.0.0.mpk`, `crashy-1.0.0.mpk`, `bike-rack-1.0.0.mpk`, `hello-menu-1.0.0.mpk`, `daemon-status-1.0.0.mpk`.

- [ ] **Step 1: Install a plugin with approval (CLI), taking over a push.**
  - `mibsdk-dev install-plugin plugins/hello/build/mibsdk/hello-1.0.0.mpk`. Expected: exit 3 with "Read vehicle state: … (vehicle.read)" and `approve with: --approve=vehicle.read`.
  - Re-run with `--approve=vehicle.read`. Expected: "loaded: ENABLED" (plus "replaced the pushed hello.jar" if it was pushed); `grants.properties` has `hello=vehicle.read`.
- [ ] **Step 2: F8 regression.**
  - While `mibsdk-dev install-plugin plugins/bike-rack/build/mibsdk/bike-rack-1.0.0.mpk --approve=vehicle.read,vehicle.parking.write,ui.slot.carmenu,ui.slot.statusbar` runs, loop `SSH /tmp/mibpkg gem-screens` five times in a second terminal.
  - Expected: install succeeds with "loaded: …"; `SSH ls /tmp | grep mibpkg.rw` is empty afterwards; `SSH mount | grep /mnt/app` shows read-only.
- [ ] **Step 3: Web approval and revoke.**
  - Upload `crashy-1.0.0.mpk` (no permissions): installs with no sheet.
  - Upload `daemon-status-1.0.0.mpk`: the sheet lists "Open network connections … (network)" and "Show an item in the status bar (ui.slot.statusbar)"; allow; "loaded: ENABLED". Screenshot.
  - On daemon-status's package view uncheck `network`: the warning shows; save → `mibpkg sdk status` lists `blocked: network`, and the Plugins menu shows it blocked. Re-check and save → ENABLED. Screenshot.
  - Upload a test-only `bike-rack` 1.0.1 that adds `network`: the sheet shows only `network`.
- [ ] **Step 4: Removal.**
  - `mibsdk-dev remove crashy`: data kept, no ledger lines.
  - Switch `hello-menu` off in the Plugins menu if pushed, then `mibsdk-dev remove hello-menu` (pushed id): "removed hello-menu: … and its hello-menu.off switch".
  - `mibsdk-dev remove hello --purge-data`: data gone after "loaded: DISABLED plugin jar not found".
  - With a bike-rack override in force (mute rear tones), `mibsdk-dev uninstall`: refused with "remove these plugins first: bike-rack, daemon-status". `mibsdk-dev remove bike-rack`: "overrides of bike-rack restored". Remove daemon-status.
- [ ] **Step 5: Uninstall and restore.**
  - `mibsdk-dev uninstall`. Expected: `bundles_dsitracer_enabled.properties` byte-equal to the Step 1 (Task 15) stock copy, mode `-rwxrwxrwx`, `HMICar=de.audi.tghu.car.hmi.Activator`; `dsitracer_activated` still present; SDK jars gone; `/mnt/persist/mibsdk`, `grants.properties` kept.
  - `mibsdk-dev restart`. Expected (after 4–5 min): `bridge_connected:true`, stock home screen and Car menu.
- [ ] **Step 6: Reinstall for continued bench work.** `mibsdk-dev install`, `restart`; install `hello`, `bike-rack`, `hello-menu`, `daemon-status` with approval; record the final state in `docs/bench-e2e.md`. Leave the unit on its home screen.
- [ ] **Step 7: Commit and follow up.**
  - SDK repo: `docs(e2e): Plan 3 verified on the bench (mibpkg 0.8.0 SDK and plugin packages)`.
  - File any F-findings as fixes in the owning repo before merging either branch.
