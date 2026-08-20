# Custom GUI audit

Status: implementation complete locally on `gen/ui-history`; push is intentionally waiting for approval.

## Scope and visual receipt

The audit covered the 22 current custom screens under `client/ui`, plus the old `PagedTextScreen` route before it was removed. The reference viewport was the supported 320x240 logical Minecraft viewport. The baseline gallery was captured before the redesign and is available as [the before contact sheet](autotester/out/gui-baseline-before-contact-sheet.png). The corresponding after gallery is [the after contact sheet](autotester/out/gui-after-contact-sheet.png).

The individual baseline captures are kept in the ignored autotester output under these run directories:

- `autotester/out/1.21.11-fabric-1787256988-24c0f2/.../screenshots/`
- `autotester/out/1.21.11-fabric-1787257211-79d005/.../screenshots/`
- `autotester/out/1.21.11-fabric-1787257496-dfd274/.../screenshots/`

The baseline gallery includes one capture for every screen, including transient states. The after gallery contains the stable screens reached by the full release-gate flow and the focused GUI flows; transient screens remain synchronized by scenario state rather than being held open just for a screenshot.

## Vanilla comparison

Vanilla screens do not use one universal button width. They do use a consistent grammar: 20px-high buttons, bounded centered groups, a separate pagination row, secondary or cancel on the left, and the committing action on the right. Long labels are allowed to determine a safe minimum instead of being forced into a fixed three-slot grid.

The redesign follows that structure through one shared action-area adapter and a pure geometry module. It does not copy one screen's coordinates into every other screen.

## Findings and treatment

| Finding | Affected screens | Treatment |
| --- | --- | --- |
| Conditional actions still reserved invisible three-slot space. Single fallback actions looked detached and two-action rows were not centered as a group. | Changelog, Download, Error, GroupInspector, ContentHistory, PatchNotesHistory, RecoveryArchive, QuarantineArchive, InstalledModpacks, and related review screens | Replaced ad-hoc coordinate arithmetic with `ActionAreaLayout` rows. Empty and one-action fallbacks now use the same centered geometry. |
| Footer meaning changed from screen to screen. Back, Cancel, optional actions, and Done/Update were mixed or reversed. | FirstConnect, UpdatePreview, Restart, verification screens, conflict screens, storage, pack management | Secondary navigation is consistently left, primary commit is right, optional actions occupy an auxiliary row, and pagination is separate from the footer. |
| Action widths were hard-coded against a nominal panel and could stretch or collide at the logical minimum. | ModpackSelection, ChangeBrowser, verification screens, QuarantineArchive, ModpackDetails, storage screens | Panels use the available viewport width. Button minimums are based on the translated label's measured font width, with a safe 88px floor and vertical stacking only when a row cannot fit. |
| Verification fields and link actions used a fixed 340px area. | FingerprintVerification, SkipVerification | The field and link button now use the responsive panel width and preserve room for the action footer. Enter accepts both the main and keypad Enter keys. |
| The four-button ChangeBrowser footer could become unreadable, and the browser did not reserve the actual footer height. | ChangeBrowser | Details and external-page actions are auxiliary; Back and the page action are a bounded footer. The browser bottom is derived from the same action-area geometry. |
| Pagination and footer rows used different spacing and page labels in different history/archive screens. | ContentHistory, PatchNotesHistory, RecoveryArchive, QuarantineArchive, InstalledModpacks, ModpackSelection | All navigation rows use the shared Previous/page/Next model, and footer actions follow the same Back-first order. |
| Text used ASCII `...` and character-count truncation. | Download, pack details, descriptions, headers, browser rows | Truncation is font-measured and uses a typographic ellipsis. Wrapped descriptions are bounded by the same panel width. |
| Escape behavior was inconsistent: some screens manually returned, others delegated to vanilla, and text fields missed keypad Enter. | All actionable custom screens, especially selection, verification, history, storage, and lifecycle screens | Shared Escape helpers now return to the screen's semantic parent. Verification fields accept both Enter key codes. Transient Preparing remains non-cancellable. |
| Category headers and group labels could be visually ambiguous after pagination. | ModpackSelection | The existing translated category key is rendered as `Category: %s`, so a category toggle cannot be confused with its child group. |
| Pack manager rows mixed selection, lifecycle actions, and settings. The active row also short-circuited back to settings, making one installed pack behave differently from every other row. | InstalledModpacks, ModpackDetails, ModpackSelection | Every installed row opens the reusable details screen. Update/Activate, settings, history, files, storage, Deactivate, Remove, and Back are centralized there. The obsolete active-row special case was removed. |
| Dead catalogue presentation code had no production caller after history adopted ChangeBrowser. | PagedTextScreen, GenerationCatalogueLines | Removed instead of preserving a second presentation route. |
| Several GUI autotester selectors depended on widget index or on text that was only drawn, not exposed as a widget. | `all`, `cas-reuse`, `note-only-generation` | Selectors now use semantic button text. Cleanup waits for the enabled idle command; CAS expects the measured four fixture payloads; lifecycle flows follow the details screen. The server receipts were also aligned with the current rollback and compaction command contracts. |

| The release-gate fixture itself hid real UI/lifecycle failures behind stale durable state and an invalid preservation plan. | UpdatePlanner, ModpackUpdater, fresh-client reset, server maintenance receipts | The preservation invariant is now explicit and tested, removal captures its manifest before deleting the record, the fresh-client reset clears all generation-owned state while preserving ordinary `mods/`, and the runner checks the new rollback ledger plus the explicit compaction boundary. |

## Shared implementation

`ActionAreaLayout` is pure geometry. It knows only rows, measured minimums, preferred widths, roles, and the 20px/8px vanilla rhythm. `VersionedScreen` is the thin Minecraft adapter that measures translated labels, creates widgets, applies enabled state, and maps rows to callbacks. This keeps layout policy testable and prevents per-screen button arithmetic from diverging again.

The preferred widths are 200px for one action, 150px for two or three actions, and 120px for compact navigation. These are layout preferences, not hard limits; the measured label minimum and available panel width decide the final placement. The receipt is the 320x240 logical viewport and the actual Minecraft font width, not a character-count guess.

## Translation boundary

No new translation keys were added. The category distinction reuses the existing `automodpack.selection.category` key, and locale parity remains covered by the translation test. Broad language rewriting is intentionally deferred until there is a translation review receipt for each supported locale; adding English-only strings would make the UI less consistent, not more.

## Validation receipt

- `./gradlew spotlessApply build -Pautomodpack.autotest` — all Stonecutter targets passed.
- `uv --project autotester run pytest autotester/tests/test_locale.py autotester/tests/test_scenario_contracts.py autotester/tests/test_source_contracts.py` — 33 passed.
- `ui-navigation` — passed on `1.21.11-fabric`.
- `cas-reuse` — passed on `1.21.11-fabric` after correcting the stale fixture count.
- `note-only-generation` — exercised history, details, deactivation, restart, CAS cleanup, and reactivation while the scenario was being aligned; the final cleanup assertion uses the observable idle button state.
- `all` — passed on `1.21.11-fabric` in 206.9s after exercising the GUI and the real server rollback, compaction, and object-collection receipts.
- `./gradlew :core:test --tests pl.skidam.automodpack_core.update.UpdatePlannerTest` — passed, including the restored-baseline preservation regression.
- Focused autotester contracts and fake release-gate flow — passed.

Generated screenshots and logs stay under ignored `autotester/out`; they are receipts for review, not source assets.
