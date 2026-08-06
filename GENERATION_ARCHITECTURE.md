# AutoModpack generations, groups, and client experience

**Status:** authoritative design and redesign target for the current generation branch

**Date:** 2026-08-03

This document defines the design for immutable modpack generations, complete group catalogues, client selection, content-addressed storage, cleanup, restore, reverts, connection-origin trust, numeric address normalization, and the client experience.

The generation, storage, group, selection, preview, recovery, transaction, client-cache, and connection-origin foundation is implemented on the current branch. The server storage layer now uses deduplicated catalogue snapshots, compact generation commits, ownership deltas, and one current complete projection for the client wire shape. A long-history probe confirms that normal load remains projection-bound while the rewritten deep/history/maintenance paths scale linearly in compact history and remove the repeated-record storage cost. The remaining product work is generation-lifecycle E2E coverage, invalid-selection repair UX, publication-cost measurement, and client payload/planning measurement. Section 24 defines the efficiency work without adding a second protocol.

The design favors simple behavior:

- The server publishes one complete catalogue for every generation.
- A group is a bundle of files and features. A group is not one mod.
- The client stores explicit group and tag choices.
- The client derives the effective group selection from the complete catalogue.
- The client applies one selected target directly.
- The server sends one cumulative ownership ledger with the target.
- The client removes only exact managed content.
- The client preserves bytes before deletion.
- A first connection uses safe defaults and offers optional customization.
- Normal client history shows effective content states.
- Server history shows the complete technical generation chain.

## 1. Product goals

The system must:

- Keep one stable identity for each modpack lineage.
- Publish immutable generation commits and one current projection.
- Keep a complete group-aware catalogue in every generation.
- Let a group contain many files and file types.
- Let groups declare dependencies, conflicts, defaults, and platform rules.
- Let a group belong to zero or one user-facing tag.
- Let the client select one group or one complete tag bundle.
- Explain group availability before file acquisition.
- Keep the first setup easy for a new player.
- Show a file preview before download and mutation.
- Show patch notes in the player-facing update flow.
- Apply one target directly after skipped generations.
- Keep historical ownership for safe cleanup.
- Preserve deleted and replaced bytes in the client CAS.
- Restore the pre-modpack baseline during removal.
- Publish server reverts as new child generations.
- Keep technical revert details out of normal client history.
- Measure storage before collection.
- Keep the existing planner and transaction executor as the only client mutation path.

## 2. Terms

### 2.1 Modpack lineage

A modpack lineage has one stable `modpackId`.

The ID identifies the content history and ownership history. It does not identify one server address.

A client can use the same lineage when the server address changes.

### 2.2 Generation

A generation is one immutable server record.

Every generation has one parent except the root generation.

The server keeps an ordered chain:

```text
A -> B -> C -> D
```

A generation contains one complete catalogue state and one cumulative ownership ledger.

### 2.3 Catalogue

A catalogue is the complete set of groups, files, tags, policies, dependencies, conflicts, defaults, and platform rules for one generation.

The catalogue is not limited to the groups selected by one client.

### 2.4 Group

A group is a named bundle of files and features.

A group can contain:

- Many mods.
- Configuration files.
- Resource packs.
- Shader files.
- Scripts.
- Client settings.
- Other published files.

The UI must not call a group a mod.

A group is the smallest user-selectable content bundle. The client cannot select arbitrary files inside a group.

### 2.5 Tag

A tag is a user-facing bundle of groups.

A group has zero or one tag.

A group without a tag belongs to the General group list.

An untagged group does not become required automatically. It uses the normal `required` and `recommended` rules.

### 2.6 Selection intent

Selection intent contains explicit player choices.

It does not contain groups that the resolver added only because of a dependency, a required rule, a forced tag, or a platform rule.

The intent stores:

- Requested tag IDs.
- Requested group IDs.
- Explicit group exclusions when the UI supports an opt-out from a selected tag.

The resolver stores derived results separately.

### 2.7 Resolved selection

A resolved selection contains:

- Explicit requested tags.
- Explicit requested groups.
- Explicit exclusions.
- Selected groups.
- Required groups.
- Dependency groups.
- Groups selected by tags.
- Forced groups.
- Stale choices.
- Unsupported groups.
- Conflict explanations.
- Dependency explanations.

The resolver does not write derived groups into explicit intent.

### 2.8 Selected target

A selected target is one flat file tree derived from a complete catalogue, selection intent, and client platform.

The client planner applies only this flat target.

### 2.9 Content object

A content object is an immutable file addressed by its SHA-1 hash and advertised size.

Equal bytes use one object in the server and client CAS.

### 2.10 Ownership ledger

The ownership ledger records every logical path that the modpack managed during its history.

Each entry keeps every historical hash and size for that path.

A path remains in the ledger after it disappears from the current target.

### 2.11 Baseline

A baseline is the client state before the modpack first takes control of a path.

A baseline contains an object reference or an absent marker.

### 2.12 Recovery archive

A recovery archive is a user-visible location for bytes preserved from the client CAS.

Recovery files are outside the managed modpack tree.

## 3. Stable generation identity

### 3.1 Modpack ID

The server creates a stable `modpackId` for the root generation.

Later normal generations keep the same ID.

A server cannot publish a new generation with a different ID in the same lineage.

### 3.2 Generation metadata

A generation record contains:

```text
schemaVersion
generationId
parentGenerationId
createdAt
modpackId
stateDigest
ledgerDigest
patchNotes
patchNotesDigest
rollbackTargetGenerationId
```

`rollbackTargetGenerationId` is empty for a normal publication.

A non-empty rollback target identifies a revert. A separate operation field is not required.

The generation ID uses a canonical length-prefixed encoder.

The server does not hash formatted JSON to create identity.

### 3.3 Catalogue state digest

The catalogue state digest covers every value that affects the complete catalogue:

- Modpack identity and public metadata.
- Group IDs.
- Group names and descriptions.
- Optional group tags.
- Required and recommended flags.
- Group dependencies.
- Group conflicts.
- Supported client platforms.
- Normalized logical paths.
- File hashes and sizes.
- File types and file policies.
- Tag metadata.
- Forced and default tag rules.

The digest does not cover:

- Generation ID.
- Parent ID.
- Publication time.
- Patch notes.
- Actor data.
- Mutable source paths.
- Scan duration.
- Server-local reports.

### 3.4 Ledger digest

The ledger digest identifies the cumulative ownership state for the generation.

A revert can reuse an older catalogue state while producing a newer ledger state.

This distinction lets long-offline clients remove files from skipped generations.

### 3.5 Target identity

A client target carries:

```text
targetGenerationId
parentGenerationId
stateDigest
ledgerDigest
modpackId
```

The client validates that the selected flat manifest, complete catalogue, generation record, and ownership ledger agree before mutation.

### 3.6 Current pointer

The server stores one current pointer:

```text
schemaVersion
generationId
```

`current.json` is the only current-state commit point.

The server never selects a record by file name or file time.

## 4. Group model

### 4.1 Group declaration

A group declaration contains:

```text
id
displayName
description
tag
required
recommended
requires
breaksWith
compatiblePlatforms
syncedFiles
allowEditsInFiles
overwriteEditableFiles
forceCopyFilesToStandardLocation
```

`tag` is optional.

`requires` is a set of group IDs.

`breaksWith` is a set of group IDs.

`compatiblePlatforms` is empty when the group supports all supported client platforms.

There is no group category field.

There are no project or source link fields.

### 4.2 General groups

A group without a tag appears in the General group list.

An untagged group follows the same selection rules as every other group:

- `required` selects it for every compatible client.
- `recommended` selects it in the default intent.
- An explicit group choice selects it.
- A dependency can select it.
- A forced rule can select it.

The absence of a tag does not force a group into every target.

### 4.3 Group content

The group directory is fixed:

```text
automodpack/host-modpack/<group-id>/...
```

A group directory can contain many file types.

The server can also populate a group from synchronized server-root paths.

Group-folder content has precedence over matching synchronized content within the same group.

The scanner records source provenance and shadow decisions in the candidate report.

An unexpected scan, read, inspection, or hashing failure fails the complete candidate.

The server never publishes mutable source paths.

### 4.4 Dependencies

A group can require multiple groups.

Dependency rules are directed from the requiring group to the required group.

The resolver computes the transitive dependency closure.

A dependency must name an existing group.

A dependency graph cannot contain a cycle.

A dependency that is unavailable on the current platform makes the requesting choice unavailable.

A required or server-forced dependency that is unavailable makes the catalogue invalid for that platform.

The UI explains every derived dependency:

```text
Selected because Performance requires Core Client Files
```

### 4.5 Conflicts

Conflict rules are symmetric in behavior.

If either group declares the other group in `breaksWith`, the resolver treats the groups as conflicting.

A group cannot conflict with itself.

A conflict is checked after dependency expansion.

A direct group choice does not silently change the persisted intent.

The UI presents the conflicting choices and lets the player remove the optional choice that caused the conflict.

Required and server-forced groups cannot be removed.

### 4.6 Same-path variants

Two groups can publish different bytes for one logical path only when the validator proves that the groups cannot be selected together on the same supported platform.

Co-selectable groups with different same-path content fail catalogue validation.

Mutually exclusive variants remain valid in the complete catalogue.

The selected flat target contains one variant.

The ownership ledger stores all variant hashes and all historical hashes for the path.

### 4.7 Platform rules

The supported client platforms are:

```text
windows
linux
macos
android
```

An empty `compatiblePlatforms` set means all supported platforms.

A group can restrict its compatible platforms.

The client derives the current platform locally.

The complete catalogue validator checks default and required selections on every supported platform.

A required group that cannot work on a supported platform makes publication invalid.

An optional group can be unavailable on one platform.

The client displays a structured reason instead of a generic disabled state.

## 5. Tag model

### 5.1 Tag declaration

A tag declaration contains:

```text
id
displayName
description
defaultSelected
serverForced
```

A tag can contain zero or more groups.

A group belongs to at most one tag.

A group without a tag belongs to General.

### 5.2 Tag selection

Selecting a tag requests every group in that tag that is compatible with the current platform.

The resolver then expands dependencies.

A selected tag remains in selection intent across generations.

If the server adds a new compatible group to a selected tag, the new group enters the resolved selection.

If a group moves to another tag, the resolver follows the new catalogue state.

If a group is removed, the intent records it as stale only when the group choice itself is no longer valid.

### 5.3 Safe tag bundles

A tag is a selectable bundle.

For every supported platform, the server validates:

1. All compatible groups in the tag.
2. The dependency closure of those groups.
3. Platform availability.
4. Conflicts in the closure.
5. Same-path ownership rules in the closure.

The server rejects a tag that contains a conflict on the same supported platform.

This gives the player a clear promise:

> Selecting a tag selects the complete compatible tag bundle.

A tag can contain groups that are exclusive by platform.

For example, one tag can contain one Windows-only group and one Android-only group.

The client selects the compatible subset and explains the unavailable groups.

### 5.4 Tag and group intent

Selection intent stores separate requested tag and group sets.

A selected tag is not replaced by its current group list in persistent intent.

A selected group is not replaced by its dependency closure in persistent intent.

The resolver derives the final set on every catalogue change.

The UI can offer an explicit exclusion for a group inside a selected tag.

An exclusion cannot remove a required or server-forced group.

An excluded group that is required by another selected group produces a visible dependency error.

## 6. Selection resolution

### 6.1 Default intent

The default intent contains:

- Required groups.
- Recommended groups.
- Groups attached to default-selected tags.
- Dependencies of those groups.

The default intent does not contain every optional group.

The first-connect flow uses this intent without requiring player decisions.

### 6.2 Forced intent

A required group is forced.

A group attached to a server-forced tag is forced.

A forced group can add forced dependencies.

A forced group cannot be removed by the client.

### 6.3 Resolution order

The resolver uses this order:

1. Load explicit tag choices.
2. Load explicit group choices.
3. Add required groups.
4. Add server-forced tag groups.
5. Expand selected tag bundles.
6. Expand dependency closures.
7. Apply explicit group exclusions.
8. Apply platform filtering.
9. Check conflicts.
10. Produce the resolved selection and explanations.

The resolver does not modify selection intent.

UI actions can propose a new intent. The player sees the proposed change before saving it.

### 6.4 Stale choices

A missing group ID becomes a stale group choice.

A missing tag ID becomes a stale tag choice.

The client keeps stale choices in the intent until the player removes them or the UI saves a cleaned intent.

The update preview shows stale choices.

The resolver never treats a missing choice as a different valid group.

### 6.5 Resolution explanations

The result contains an explanation for each group:

```text
Explicitly selected
Selected by tag
Required by the server
Recommended default
Required by another group
Excluded by the player
Unavailable on this platform
Blocked by a dependency
Conflicts with another choice
Stale selection
```

The client uses these explanations in the group and update screens.

## 7. Client experience

### 7.1 First connection

The client fetches the complete catalogue before acquiring content files.

The client validates the catalogue before showing selection controls.

The client then shows a welcome screen with:

- Modpack name.
- Patch notes for the target generation.
- Recommended group and tag choices.
- Number of selected groups.
- Approximate file count.
- Approximate download size.
- Platform compatibility summary.
- A short explanation that groups are bundles of files and features.

The screen has two clear actions:

```text
Continue with defaults
Customize groups
```

`Continue with defaults` is the primary action.

`Customize groups` opens the selection screen.

The client does not acquire content objects before the player confirms the target.

The complete catalogue itself can be fetched before confirmation because it is required for selection and preview.

### 7.2 Group selection screen

The group screen contains:

- A General section for untagged groups.
- One section for each tag.
- A group row for every group.
- Group description.
- File count.
- Approximate content size.
- Required and recommended state.
- Compatibility state.
- Dependency state.
- Conflict state.
- Selection explanation.

The screen supports:

- Select one group.
- Select one complete tag.
- Remove an optional group.
- Remove an optional tag.
- Inspect group contents.
- Reset to recommended defaults.
- Save the intent.

The screen does not expose individual file selection.

A group can contain many files. The UI presents the group as one coherent choice.

### 7.3 Compatibility display

The group row uses clear status text:

```text
Available
Required
Recommended
Selected by tag
Requires: <group>
Unavailable on Linux
Unavailable because <group> is unavailable
Conflicts with: <group>
Forced by server
```

An unavailable optional group is disabled.

The tooltip or detail panel explains the exact rule that caused the state.

The UI does not require the player to read logs to understand a disabled group.

### 7.4 Group inspector

The inspector shows:

- Group name.
- Group description.
- Tag or General status.
- Required and recommended state.
- Dependencies.
- Conflicts.
- Platform availability.
- File count.
- File paths inside the group.
- File types.
- File sizes.
- Editable and copied-file policies.

The inspector does not show project or source links.

The inspector does not permit arbitrary file selection.

### 7.5 File update preview

After the selection is resolved, the client builds the normal update plan.

The preview appears before content acquisition and before file mutation.

It shows:

- Patch notes.
- Selected tags.
- Selected groups.
- Added files.
- Changed files.
- Removed files.
- Files preserved because local bytes changed.
- Files preserved in the CAS.
- Baseline restorations.
- Unsafe file types.
- Download size.
- Resulting restart reasons.

The preview does not show generation IDs in normal text.

The preview does not use a second update engine.

### 7.6 Default-first behavior

A new player can accept the default target without opening advanced controls.

A player who opens customization sees the consequences before confirmation.

The client does not force a player to understand tags, dependencies, or conflicts to install a valid default target.

### 7.7 Update behavior

A later update fetches the new complete catalogue and target.

The client preserves valid selection intent.

The resolver derives groups again from the new catalogue.

The preview shows:

- New groups selected by a remembered tag.
- Groups that became unavailable.
- Stale group and tag choices.
- New dependency consequences.
- Conflict consequences.
- File changes caused by the resolved target.

## 8. Patch notes and history

### 8.1 Patch notes

The server can attach patch notes to a generation.

Patch notes are normalized, bounded, and included in generation identity through their digest. The current 16 KiB UTF-8 cap is a human-input tripwire, not a storage budget; the boundary receipt is `GenerationPatchNotesTest.fileNotesRequireStrictUtf8AndBoundedSize`, which rejects the first byte above the cap. Stable reads and source snapshots use three attempts as bounded-concurrency tripwires; the receipts are `GenerationPatchNotesTest.unchangedFileNotesCanBeConsumedAndChangedFileIsPreserved` and `ModpackCandidateScannerTest.sourceMutationAfterEveryCopyExhaustsRetriesWithoutLeakingStagedFiles`. These values must not be increased without a new measurement and a matching test receipt.

The client receives the notes with the target record.

The client shows the notes in:

- First-connect welcome screen.
- Update preview.
- Friendly client content history.

Patch notes do not create a generation by themselves when the candidate has no semantic change.

### 8.2 Client content history

The normal client history is a content history.

It stores effective content states, not every technical generation event.

When a later target returns to an earlier content state, the history collapses the repeated state.

Example:

```text
A -> B -> C -> D
```

When `D` returns to the content state of `B`, the client shows:

```text
A -> B
Current content: B
```

The client history can show:

- Modpack name.
- Patch notes.
- Effective content state.
- Selected tags.
- Selected groups.
- Recorded time.
- Human-readable file summary.

The client history does not show:

- Generation IDs.
- Parent IDs.
- Internal revert labels.
- Ledger details.
- Storage details.

### 8.3 Server technical history

The server keeps the complete parent chain.

The administrator history view shows:

- Generation ID.
- Parent generation.
- Publication time.
- Content state digest.
- Ledger digest.
- Patch notes.
- Revert target.
- Operation inferred from the rollback target.

The server history remains the source for technical audit.

The server command can expose the history to operators:

```text
/automodpack generate history
```

### 8.4 Human-readable changes

The server already computes file and metadata differences.

The client preview converts those differences into user-facing text.

Future changelog output can group changes by:

- Added group.
- Removed group.
- Changed group.
- Added dependency.
- Removed dependency.
- Changed compatibility.
- Added files.
- Removed files.
- Changed files.

The normal client view does not expose internal generation terminology.

## 9. Direct client convergence

The client does not replay missed generations.

If the installed target is `A` and the selected server target is `D`, the client plans:

```text
A -> D
```

The client does not download or apply `B` and `C` as separate transactions.

The server ledger contains the historical ownership needed to clean paths from skipped generations.

The transaction records the old target and final target.

The client writes the final target metadata only after file mutation succeeds.

## 10. Ownership ledger

### 10.1 Purpose

The ownership ledger replaces arbitrary cleanup lists.

The old `nonModpackFilesToDelete` field does not exist in the final model.

The ledger answers:

> Did this modpack publish this logical path with these exact bytes at any time in its history?

The ledger does not contain arbitrary client deletion commands.

### 10.2 Logical keys

The catalogue uses:

```text
(groupId, logicalPath)
```

The cleanup projection uses:

```text
(modpackId, logicalPath)
```

The path-level entry joins all group provenance and all historical hashes for one flat path.

### 10.3 Ledger entry

A ledger entry contains:

```text
modpackId
logicalPath
historicalHashes
historicalGroupIds
firstPublishedGenerationId
lastPublishedGenerationId
currentStatus
```

`historicalHashes` contains SHA-1 and size pairs.

`historicalGroupIds` contains every group that published the path.

`currentStatus` is `PRESENT` or `TOMBSTONE`.

A tombstone keeps historical hashes.

A path can return from `TOMBSTONE` to `PRESENT`.

### 10.4 Variant entries

A complete catalogue can contain mutually exclusive groups with different content at one path.

The ledger stores every current variant hash in the path entry.

The ledger also stores every current and historical group ID.

The client selected target still contains one resolved variant.

Cleanup matches any historical hash and exact size for the path.

### 10.5 Ledger materialization

The server derives a ledger delta from the current catalogue and parent ledger.

The delta records:

- New paths.
- New historical hashes.
- Path removal.
- Path return after a tombstone.
- Group ownership changes.
- Variant changes.

The server materializes one cumulative ledger for the current client projection. Compact history reconstruction applies deltas into one mutable ledger builder and materializes only the requested final state; it does not create a cumulative immutable ledger for every intermediate generation.

The client receives the full cumulative ledger, not only the latest delta.

## 11. Safe cleanup

A local path is a cleanup candidate only when:

1. The path is absent from the selected target.
2. The path exists in the cumulative ledger.
3. The path is normalized.
4. The path is inside a managed root.
5. The local entry is a regular file.
6. The local size matches a historical ledger size.
7. The local SHA-1 matches a historical ledger hash.
8. The path is not player-local.

The client leaves the path in place when any condition fails.

The cleanup path does not delete directories, symbolic links, or special files.

Changed local files remain in place.

### 11.1 Managed roots

Managed roots are game-relative paths controlled by the update planner. The active loader projection is a separate fixed root and is not a second live-path namespace.

The player-local area is never managed.

Player-local files include, as applicable:

- Saves.
- Screenshots.
- Logs.
- Server resource packs.
- AutoModpack recovery data.
- Other explicitly protected local areas.

### 11.2 Preserve before delete

Before deleting a cleanup candidate, the client:

1. Copies the exact local bytes to the client CAS.
2. Verifies the copied object by size and SHA-1.
3. Records the preservation in the durable transaction state.
4. Deletes the live file only after verification succeeds.

The transaction replay path treats an already verified preserved object as complete, so a crash between the copy and delete is safe. The object collector pins every planned preservation while the transaction exists. The first retention policy keeps preserved objects indefinitely.

### 11.3 Cleanup preview

The preview groups results as:

```text
Removed by the modpack
Preserved in the client CAS
Preserved because the file changed
Preserved because the path is outside managed roots
Not removed because the file type is unsafe
```

The preview shows logical paths and sizes.

The preview does not show server source paths.

## 12. Baseline and removal

### 12.1 Baseline capture

Before the first overwrite or deletion for a live path, the client captures its baseline.

For an existing regular file, the client stores the bytes in the CAS.

For an absent path, the client stores an absent marker.

For a symbolic link or special file, the client fails safely instead of replacing it silently.

Later generations do not replace the first baseline for the same ownership period.

### 12.2 Removal transaction

The client provides:

```text
Remove modpack and restore instance
```

The action uses the existing planner and transaction executor.

For every managed path:

- Restore the baseline when the current file still matches managed content.
- Delete the path when the baseline is absent and the current file still matches managed content.
- Preserve and report the file when the current bytes changed.
- Preserve the file when the baseline object is unavailable.
- Leave player-local paths untouched.

The client writes removal metadata only after safe mutations complete.

The removal action does not remove recovery archive objects. A recovery archive stores its own verified byte copy under its recovery root; it is not a manifest-only reference to the client CAS.

### 12.3 Single-file recovery

The client can recover one preserved object to the recovery archive. Recovery export copies the bytes to `recovery/<modpackId>/objects/<sha1>` before recording the manifest, so later CAS collection cannot invalidate the archive.

The action does not write the file back into its former managed path.

A later modpack update does not delete the recovered archive file.

## 13. Server reverts

### 13.1 Revert model

Given:

```text
A -> B -> C
```

A revert to `B` creates:

```text
A -> B -> C -> D
```

Generation `D` has:

```text
parentGenerationId = C
generation catalogue state = B catalogue state
ledger state = cumulative ledger through D
rollbackTargetGenerationId = B
```

The server does not move `current.json` backward.

The server does not change operator group directories during a revert.

### 13.2 Revert publication

The server validates that the target generation exists in the current parent chain.

The server validates every referenced object before the current pointer changes.

The server creates the new cumulative ledger from the previous ledger and target catalogue.

The server publishes the child record and current pointer through the normal publication lock.

A missing or corrupt object stops the revert before the pointer changes.

### 13.3 Client revert behavior

The client receives the new child generation as a normal target.

The client performs a normal direct update.

The client does not run a special rollback updater.

Valid explicit group and tag intent is resolved against the reverted catalogue.

The normal client UI does not label the update as a revert.

## 14. Server storage

The logical storage layout is:

```text
automodpack/
├── host-modpack/
│   ├── <group-id>/
│   └── ...
│
└── host-generations/
    ├── current.json
    ├── commits/                  # compact generation envelopes
    ├── catalogues/               # deduplicated complete catalogue snapshots
    ├── deltas/
    ├── current-projection.json
    ├── objects/
    ├── staging/
    └── reports/
```

The current implementation stores one complete current projection for the client and one compact commit, catalogue snapshot, and ownership delta per generation. Historical generations do not repeat the cumulative ledger. Deep recovery applies the compact chain into one mutable ledger state; technical history uses lightweight catalogue/metadata entries; revert lookup reads the target catalogue without reconstructing its historical ledger. Normal serving still uses the complete current projection and the initial client wire shape. Section 24 defines the remaining publication and client efficiency work.

### 14.1 Immutable objects

The server snapshots source files into staging.

It validates stable source attributes and staged bytes.

It promotes staged files into the immutable object store by SHA-1.

It never hard-links a published object to a mutable operator source.

Equal bytes use one object.

### 14.2 Publication order

Publication uses one exclusive lock.

The order is:

1. Load and validate the current pointer.
2. Load and validate the current record.
3. Build an isolated candidate.
4. Validate the complete catalogue.
5. Snapshot and validate staged objects.
6. Compute catalogue identity.
7. Compute the ownership delta.
8. Materialize the cumulative ledger.
9. Return `NO_CHANGES` when the semantic state is unchanged.
10. Select the parent generation.
11. Create the immutable generation record.
12. Promote missing objects.
13. Validate every referenced object.
14. Write the immutable record.
15. Replace `current.json` atomically.
16. Activate the hosting map.
17. Write derived reports.
18. Consume matching patch notes.
19. Remove abandoned staging files.

Before the pointer replacement, the old generation remains current.

After the pointer replacement, every referenced record and object exists.

### 14.3 No-change publication

A candidate with the same catalogue and ledger state returns `NO_CHANGES`.

The server does not create a generation.

The server does not move the pointer.

The server does not consume pending patch notes.

A patch note alone does not create a generation.

### 14.4 Storage report

The server can measure:

- Immutable object count and bytes.
- Staging file count and bytes.
- Referenced object count and bytes.
- Logical object reference count.
- Unique-object ratios.

Measurement is read-only.

### 14.5 Explicit server collection

The server can collect unreachable immutable objects through an explicit maintenance operation.

The collector retains:

- The current generation.
- Caller-pinned generation IDs in the current compact lineage.
- Caller-pinned object hashes.
- Objects referenced by retained compact states and ledgers.

The collector deletes only regular, non-symbolic-link files with canonical SHA-1 names.

The collector verifies object contents before deletion.

The collector never deletes compact generation metadata.

The collector does not use an age limit or guessed byte threshold.

Collection is never automatic.

### 14.6 Client retention

The client storage boundary is:

```text
automodpack/
├── client/
│   ├── records/<id>/             # one immutable manifest.json per generation
│   ├── overlays/<modpackId>/     # changed editable files only
│   ├── baselines/<modpackId>/    # first-takeover baseline
│   ├── active/                   # fixed current loader projection
│   ├── incoming/<transaction>/   # prepared projection
│   ├── backup/<transaction>/     # swap recovery projection
│   ├── recovery/<modpackId>/     # manifest plus independent recovery objects
│   ├── active-state.json         # current projection identity
│   └── selections.json           # explicit group and tag choices
├── client-config.json            # selected modpack and connection settings
└── data-root.json                # pinned shared-or-local data-root location

<data-root>/
├── objects/                      # immutable SHA-1 objects
├── file-metadata/                # derived exact-stat hash cache
├── mod-metadata/                 # derived content-keyed mod cache
├── packs/                        # derived downloaded pack cache
└── known-hosts.json              # connection-origin trust state
```

The client uses immutable generation records, active state, overlays, baselines, recovery archives, and the cumulative ownership ledger as its reference roots. After a successful update or modpack removal, it retains hashes from every generation record, ownership history, overlay, baseline, and current standard-directory mod. It removes only unreachable regular files with canonical SHA-1 names from the pinned data root's `objects/`. Transaction workspaces are cleaned by transaction ID and are never treated as CAS staging. Recovery archive objects are validated from their own root and are not CAS references.

This is exact reachability maintenance, not age- or size-based retention. Historical ledger hashes remain retained because they support precise cleanup and recovery. Recovery archives are separate roots and do not depend on the client CAS after their copy completes.

The client also has two independent persistent caches under `cache/`: `FileMetadataCache` avoids repeated content hashing when exact filesystem metadata is unchanged, and `ModFileCache` stores parsed mod metadata by content SHA-1 rather than path. Both are derived data and can be rebuilt. File metadata uses exact timestamp precision, file identity, size, a conservative racy-timestamp check, and an after-hash stability check; it is not an integrity boundary for a file whose bytes and all exposed attributes are changed together.

## 15. Client transaction architecture

The existing `UpdatePlanner` and `UpdateTransactionExecutor` remain the only client mutation path.

The UI can display plans and start transactions.

The UI cannot directly mutate files.

The transaction order is:

1. Load the installed state.
2. Load and validate the complete target bundle.
3. Load explicit selection intent.
4. Resolve tags, groups, dependencies, conflicts, and platform rules.
5. Compose the selected flat target.
6. Compute additions, changes, and removals.
7. Compute ledger cleanup candidates.
8. Capture baseline objects before the first live mutation.
9. Acquire required target objects in the client CAS.
10. Preserve every cleanup candidate in the client CAS.
11. Apply staged file changes.
12. Apply guarded deletions.
13. Build and verify the incoming active projection from CAS links.
14. Atomically swap the fixed active projection and retain the old directory as the transaction backup.
15. Write client config, active generation state, and selection intent.
16. Mark the transaction complete and remove the transaction workspace.

The final metadata write is the installed-state commit point.

An interrupted transaction leaves the old state authoritative, the new state authoritative, or a resumable transaction.

The client never publishes server state.

## 16. Protocol shape

A current target response contains:

```text
generation record
complete catalogue
ownership ledger
catalogue state digest
ledger digest
state digest
patch notes
```

The client validates all relationships before planning.

The object request accepts only a canonical 40-character SHA-1 key.

The protocol never accepts an arbitrary server file path.

The client receives one complete current target and one cumulative ledger.

The client does not receive or apply skipped generations.

The current protocol intentionally has no history-delta negotiation or chunked second path. The complete projection is self-contained and can be published as one static document, while SHA-1 object names can be served as static files. Any future transport replacement requires a separate protocol version and payload/planning receipt; it must preserve one atomic target transaction.

## 17. Security and path rules

All managed paths must be canonical normalized logical paths.

The server and client reject:

- Absolute paths.
- Drive-qualified paths.
- Parent traversal.
- Reserved metadata paths.
- Windows illegal components.
- Case aliases on case-insensitive platforms.
- Symbolic-link escapes.
- Special file types in file mutation paths.

The server validates source and staged bytes by exact size and SHA-1.

The client validates every CAS object by exact size and SHA-1.

Managed roots cannot be symbolic links.

Recovery roots cannot contain symbolic-link path components.

The server never serves mutable operator source paths.

## 18. Performance rules

The server scans each synchronized root once per candidate.

It derives minimal synchronized scan roots.

It keeps scan roots inside the configured server root.

The candidate stores descriptors and staged object references instead of duplicate byte arrays.

The client planner operates on manifests and object references.

The client does not load every historical file version into memory.

The server and client use one object for each unique hash.

The system adds no storage cap, history cap, or arbitrary age limit without a measured receipt.

Before adding a limit, measure:

- Total object bytes.
- Unique object ratio.
- Catalogue and ledger size.
- Generation count.
- Generations per day.
- Client update frequency.
- Client CAS bytes.
- Baseline bytes.
- Recovery usage.
- Scan duration.
- Planning duration.
- Transfer duration.

The current branch has a diagnostic before/after long-history receipt. At 1024 generations with one changing logical path, the old store was 62.6 MB and the rewritten store was 2.08 MB; deep load improved from 267 ms to 94 ms, technical history from 489 ms to 63 ms, and storage measurement from 1.29 s to 143 ms. Normal load remained about 1 ms. The rewritten publication loop took 2.59 s for the 1024-generation fixture, including candidate creation, object promotion, JSON writes, and expected-current loads. This fixture is intentionally synthetic and is not a production budget. A performance change must add realistic pack sizes, publication, and client payload/planning measurements before choosing concurrency, checkpoint, retention, or projection limits.

## 19. Implementation status

### 19.1 Implemented foundation

The current branch contains:

- Stable modpack IDs.
- Immutable generation commits and current projection.
- Parent-linked history.
- Atomic current pointer publication.
- Complete group catalogue storage.
- Group dependencies, conflicts, defaults, and platform rules.
- Direct client convergence.
- Cumulative ownership ledgers.
- Historical hash and size cleanup.
- Mutually exclusive same-path variant ownership.
- Preserve-before-delete CAS handling.
- Baseline capture.
- Safe modpack removal.
- Recovery archive export and UI.
- Server reverts.
- Server technical history command.
- Client effective content history.
- Storage measurement.
- Explicit server object collection.
- Client `client/records`, fixed `client/active`, and pinned data-root `objects` storage with reference-based collection.
- Exact-stat file metadata caching and content-keyed parsed-mod caching in client planning.
- Group inspection.
- Basic update preview.
- Basic group selection.
- Group and tag management with structured resolution failures.
- Patch notes and human-readable generation changes.
- Client removal, baseline restoration, and recovery UI.
- Autotester sync, restart, reconnect, and in-game coverage.

### 19.2 Current product gaps

The branch still needs:

- An unresolved-selection repair screen or an explicit decision to defer it.
- A real generation-lifecycle autotester scenario.
- Representative publication and client payload/planning performance receipts.
- A decision on persisted candidate reports.
- A server retention/pinning policy beyond conservative orphan-object collection.
- A visual minimum-size and localization pass.
- A release decision for existing server-config migration.
- Representative client payload/planning receipts; the current complete-projection protocol remains the deliberate default.

### 19.3 Implemented client experience; remaining verification

The branch contains:

- First-connect default screen.
- Pre-download group customization.
- General group section.
- Tag bundle selection.
- Group cards with file count and size.
- Compatibility explanations.
- Dependency explanations.
- Conflict explanations.
- Patch notes in first-connect and update previews.
- Human-readable content changes.
- Friendly client content history with patch notes.
- Clear default and customize actions.
- Localized UI text.
- Accessible navigation on all supported Minecraft versions.

The remaining work is visual verification at minimum dimensions, long-text states, and invalid persisted selections.

### 19.4 Implemented server experience; remaining verification

The branch contains:

- Clear group and tag configuration documentation.
- Generation preview with group consequences.
- Human-readable generation change summaries.
- Technical history output with patch notes.
- Revert confirmation with target summary.
- Storage receipt output.
- An end-to-end sync autotester scenario.

The generation-specific lifecycle scenario remains outstanding.

## 20. Critical tests

### 20.1 Group data

Test:

- Optional tag values.
- Untagged General groups.
- One tag maximum per group.
- Safe group IDs.
- Safe logical paths.
- Missing group dependencies.
- Dependency cycles.
- Conflict symmetry.
- Required and recommended groups.
- Forced tags.
- Platform filtering.
- Same-path shared files.
- Same-path mutually exclusive variants.
- Group folder precedence.
- Fatal scan errors.

### 20.2 Selection intent

Test:

- Requested tags persist across generations.
- Requested groups persist across generations.
- Derived dependencies do not enter explicit intent.
- Stale groups remain visible.
- Stale tags remain visible.
- Required groups cannot be excluded.
- Forced groups cannot be excluded.
- Selecting a tag resolves all compatible groups.
- A new group under a selected tag enters the next target.
- An incompatible group is explained.
- A conflicting tag bundle is rejected by validation.
- A direct group choice reports cross-tag conflicts.

### 20.3 Generation identity

Test:

- Stable digest under input reordering.
- JSON formatting independence.
- Group metadata changes.
- Tag metadata changes.
- Dependency changes.
- Platform rule changes.
- Ledger digest changes.
- Parent changes.
- Revert child identity.
- Patch note digest.
- Report and timing independence.
- `NO_CHANGES` behavior.
- Pending note preservation after no change.

### 20.4 Immutable storage

Test:

- Source change during copy.
- Source replacement during copy.
- Symbolic-link substitution.
- Retry exhaustion.
- Staged and served hash equality.
- Size and SHA-1 validation.
- Object reuse.
- No mutable-source hard links.
- Directory flush fallback.
- Current pointer replacement ordering.
- Missing object rejection during revert.

### 20.5 Ownership ledger

Test:

- Path addition.
- Path replacement.
- Path removal and tombstone retention.
- Path return after tombstone.
- Multiple historical hashes.
- Multiple group owners.
- Mutually exclusive variants.
- Variant transitions.
- Ledger rebuild from the parent chain.
- Ledger digest mismatch.
- Long skipped history.
- Exact path, size, and hash cleanup.
- Equal hash with different size preservation.
- Unknown path preservation.
- Player-local preservation.
- Symbolic-link preservation.
- Directory preservation.

### 20.6 Preserve and recovery

Test:

- Preservation before deletion.
- CAS object validation before deletion.
- Interrupted preservation.
- Interrupted deletion.
- Recovery archive export.
- Recovery stays outside the managed tree.
- Recovery does not change the selected target.
- Object reuse without duplicate CAS bytes.

### 20.7 Baseline removal

Test:

- Absent baseline deletion.
- Existing baseline restoration.
- Changed current file preservation.
- Missing baseline preservation.
- Symbolic-link protection.
- Player-local protection.
- Interrupted removal.
- Metadata commit after mutation.

### 20.8 Reverts and history

Test:

- `A -> B -> C` revert to `B` creates `D`.
- `D.parent = C`.
- `D` uses `B` catalogue state.
- `D` keeps cumulative ledger history through `D`.
- Client direct update from `A` to `D`.
- Explicit tag intent survives a revert when valid.
- Explicit group intent survives a revert when valid.
- Client history collapses equal effective content states.
- Client history shows patch notes without technical IDs.
- Server history shows the full technical chain.

### 20.9 Client transaction

Test:

- Catalogue, target, ledger, and generation mismatch rejection.
- Direct skipped-generation convergence.
- First-connect default target.
- First-connect customized target.
- Tag selection before file acquisition.
- Target preview before download.
- Patch notes in the preview.
- Installed manifest written last.
- Ledger written with the target.
- Interrupted transaction recovery.
- No client-triggered server publication.

### 20.10 Publication recovery

Inject failure after every publication step.

Validate that the current pointer references a complete old or new generation.

Validate that startup does not guess a replacement record.

## 21. Non-goals

The first implementation does not provide:

- Arbitrary client file deletion commands.
- File selection inside a group.
- Mutable source-path downloads.
- Git branches or merges.
- Client detached checkout.
- Technical generation IDs in normal client history.
- Age- or size-based client CAS retention; client collection is reference-based only.
- Automatic recovery into an old managed path.
- A second client update engine.

## 22. Final invariants

The design is correct only while these statements remain true:

1. `host-modpack/` contains mutable operator input only.
2. Published clients never download mutable source paths.
3. One generation contains one complete group catalogue.
4. A group is a bundle of files and features, not one mod.
5. A group can have zero or one tag.
6. An untagged group uses normal required and recommended rules.
7. Selection intent stores explicit tag and group choices.
8. Resolved groups remain derived state.
9. Dependencies use group IDs and cannot cycle.
10. Tag dependency closures contain no conflict on a supported platform.
11. Platform incompatibility is explained to the player.
12. Every content object is immutable and validated by size and SHA-1.
13. Every generation has one parent except the root.
14. `current.json` is the only current-state commit point.
15. The pointer moves only after referenced objects, compact metadata, and the current projection are durable.
16. A semantic no-op creates no generation.
17. A revert creates a new child generation.
18. A client failure never publishes server state.
19. The client converges directly to the selected target.
20. The server sends the full cumulative ownership ledger.
21. `nonModpackFilesToDelete` does not exist.
22. Cleanup requires exact managed path, size, and historical hash.
23. The client preserves bytes in its CAS before deletion.
24. Single-file recovery writes to an archive outside the managed tree.
25. Modpack removal uses a captured baseline.
26. Player-local files are never managed.
27. The existing planner and transaction executor are the only client mutation path.
28. Technical revert records stay out of normal client history.
29. Patch notes appear in the friendly client update flow.
30. Server history retains the complete technical chain.
31. Server history and client historical object references remain until an explicit, measured retention policy changes them; unreachable client objects are collected by reference.
32. No future limit enters the code without a measurement receipt.
33. The first-connect default path requires no group knowledge.
34. The player sees group, dependency, conflict, and platform consequences before mutation.

## 23. Bottom line

The server stores an ordered immutable history.

The catalogue describes complete group bundles.

Tags provide simple user-facing bundles without replacing explicit group choices.

Untagged groups remain general groups with normal defaults.

Dependencies and platform rules produce explainable resolved selections.

The client applies one final target instead of replaying generations.

The cumulative ledger gives direct convergence the historical ownership data it needs.

The client preserves every deleted object before deletion.

The user can recover one file to an archive or remove the modpack and restore the old baseline.

The first connection is simple by default and still supports full group customization.

Patch notes and effective content history help players understand changes.

Technical generation history remains available to server operators.

The design keeps one transaction engine and avoids a second updater.

## 24. Efficiency redesign target

### 24.1 Why the current record shape must change

The current client projection contains a complete catalogue and cumulative ownership ledger. Historical generations do not repeat that state. The store writes `current-projection.json` plus `commits/<generationId>.json`, `catalogues/<stateDigest>.json`, and `deltas/<generationId>.json`; there is no per-generation complete record path. `GenerationStore.loadCurrent()` reads one materialized projection and verifies active manifest objects, while projection repair reconstructs the current state from compact metadata. Deep verification applies the compact chain into one mutable ledger state and checks the final current ledger commitment. Technical history returns lightweight catalogue/metadata entries. SHA-1 remains the single identity algorithm in this branch.

The current projection and client payload still grow with the unique historical hashes required for exact cleanup. The rewritten server history paths no longer multiply that state across every stored generation. The diagnostic fixture reduced 1024-generation storage from 62.6 MB to 2.08 MB, deep load from 267 ms to 94 ms, technical history from 489 ms to 63 ms, and storage measurement from 1.29 s to 143 ms. Publication throughput with a large current ledger and real client payload/planning cost still need representative receipts.

The redesign keeps the semantics and changes the storage planes:

```text
host-generations/
├── current.json                 # atomic pointer: current generation identity
├── commits/                     # small immutable generation commits
├── catalogues/                  # complete snapshots keyed by state digest
├── deltas/                      # one ownership delta per commit
├── current-projection.json      # materialized current catalogue/ledger
├── objects/                     # immutable SHA-1 content objects
├── indexes/                     # derived object/retention/measurement data
├── reports/                     # candidate and operator diagnostics
└── staging/
```

`commits/` is the compact technical history foundation. A commit contains parent identity, catalogue snapshot identity, ownership delta identity, patch-note metadata, and state digests. It does not contain a repeated cumulative ledger. The current store reads it as the authority for deep recovery, technical history, revert lookup, and projection repair.

`catalogues/` stores complete catalogue snapshots by digest. Reverts can reuse an existing snapshot without copying its JSON into a new record.

`deltas/` stores the path-level ownership change from the parent target to this target. The current branch serializes each delta with a canonical SHA-1 digest and uses it to reconstruct the cumulative ledger for deep recovery, projection repair, and explicitly requested retained states. The compact commit records the delta digest; historical complete ledgers are not stored.

`current-projection.json` is a derived, materialized view. It contains the complete catalogue and cumulative ownership ledger needed by the current client protocol. The current pointer names the authoritative generation identity. A normal load reads one current projection and verifies the active object index. It does not replay the full ancestry.

Older projections are not part of the default hot path. If an interrupted write leaves the pointer and projection out of agreement, the store rebuilds the projection from the authoritative compact commit, catalogue snapshot, and deltas, then repairs it. Full ancestry and byte verification remain available as explicit maintenance verification.

### 24.2 Publication and recovery invariants

Publication becomes:

1. Load the current pointer and projection.
2. Build a stable candidate and compute the catalogue state digest.
3. Compute one ownership delta against the current materialized ledger.
4. Return `NO_CHANGES` when both semantic state digests are unchanged.
5. Write or reuse immutable objects and the catalogue snapshot.
6. Write the ownership delta and compact commit. Materialize only the current projection needed by the client wire shape; do not write a repeated complete historical record.
7. Materialize and verify the new current projection.
8. Verify active target object presence and metadata.
9. Atomically replace the pointer with the new generation ID.
10. Activate only the current-target serving index and write derived reports.

Before pointer replacement, the old pointer remains authoritative. After pointer replacement, the new compact commit, catalogue, delta, projection, and active objects are durable. A crash can leave orphaned immutable artifacts; it must never make the store guess which generation is current. If the projection is missing or mismatched, rebuild it from the pointer’s compact chain under the repair lock.

The compact commit is the direct read authority for technical history and recovery. It carries the parent commit identity plus the identities of its catalogue, ownership delta, notes, and rollback metadata. The current envelope validates those references against the generation identity. Deep verification applies the compact chain once into a mutable ledger builder and checks the resulting current ledger commitment; it does not create a complete cumulative ledger for every intermediate generation.

### 24.3 Active serving versus retained history

The active serving set is the object set in the current target catalogue. Retained history is a separate operator policy. Historical ledger hashes are metadata for client cleanup and recovery; they are not automatically active server downloads. The current `NettyServer` enforces this boundary by resolving SHA-1 keys only through the active hosting map; it no longer falls back to the retained object root.

Explicitly pinned rollback targets can be validated before a revert and become active when the revert commits. This preserves the current testable secret-authenticated behavior while removing the unnecessary memory and retention coupling.

The collector remains explicit and previewable. It must account for:

- The current target.
- Pinned rollback/history targets.
- In-progress publication and transactions.
- Any deliberately supported historical serving operation.

No age or byte threshold enters the implementation without a measurement receipt and an operator-facing policy.

### 24.4 Candidate and client cost model

The candidate scanner should first keep correctness and add measurements for source count, staged bytes, peak result count, scan duration, hashing time, Murmur time, retries, and duplicate objects. If receipts show pressure, use a bounded completion queue and a disk-backed candidate index rather than one future and one retained result per source.

The largest safe candidate optimization is a short-lived candidate lease. A preview may retain staged objects plus source fingerprints, configuration digest, and parent identity. Publish can reuse it only after rechecking the source fingerprint and all guards. Any source or configuration change invalidates the lease and forces a fresh scan.

On the client, keep `UpdatePlanner` and `UpdateTransactionExecutor` as the only mutation engine. Add a reusable plan context with:

- The live-file metadata/hash snapshot.
- Immutable target-mod analysis.
- Immutable nested-copy analysis keyed by target state, loader, and platform.
- CAS availability.

The preview plan and post-download plan may reuse immutable target analysis. The post-download pass must still refresh live-file preconditions and revalidate the final transaction. That is the correct safety boundary.

Keep the current complete-ledger response as the protocol boundary. It is self-contained, easy to cache or publish from static HTTP, and does not require the client and server to agree on an ancestry negotiation. Measure payload and planning cost for representative packs, but do not add an incremental history response or a second client path in this branch. If the receipt eventually justifies a transport redesign, make it a separately versioned protocol that materializes the same final ledger before planning.

### 24.5 Verified object index and digest migration

The server and client should keep one flat canonical SHA-1 object namespace in this branch. A verified object index may remove repeated byte scans, but it must not become a second source of truth or a second serving path. Sharding or algorithm-tagged paths are future scale/security work only if a measurement or security decision requires them.

The current branch stays entirely on SHA-1. Do not add a partial or dual-hash path to the generation redesign.

If a future one-year security review justifies SHA-256, make it one coordinated cutover across content IDs, object paths, generation/state/ledger identity, protocol fields, client/server stores, caches, tests, and migration tooling. Do not reinterpret a legacy SHA-1 object as a SHA-256 object or silently reuse one namespace for the other. Any external SHA-1-only service should be handled at an explicit integration boundary during that separate migration.

This migration is strategically important within the one-year architecture horizon, but it is a separate schema/protocol tranche from the commit/projection redesign. Do not combine both in one unmeasured change.

### 24.6 Required receipts before optimization limits

The next benchmark fixture must vary generation count, files per catalogue, historical variants per path, object count, total bytes, and publication frequency. It should record:

- Current-load time and allocations.
- Publication time split into scan, hash, materialization, promotion, and validation.
- Current-history and storage-report time.
- Projection rebuild time after an injected interruption.
- Candidate peak staged/result state.
- Client preview, acquisition, nested analysis, and final-plan time.
- Active serving lookup latency with retained history.

Use those receipts to choose checkpointing, cache invalidation, concurrency, and retention policy. A number without its fixture and measurement is not an architecture limit.
