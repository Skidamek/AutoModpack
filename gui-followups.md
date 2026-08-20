# GUI follow-ups and deferred work

This audit intentionally improves the shared structure before adding features. The items below are findings that were considered but are not part of this implementation unless explicitly marked complete.

## Completed removals

- `PagedTextScreen` was removed. It had no production callers after generation history used `ChangeBrowserScreen`, and retaining it would preserve a second pagination and action grammar.
- `GenerationCatalogueLines` was removed with that dead route. The live catalogue projection remains in the shared ChangeBrowser flow.
- The installed-pack active-row shortcut was removed. It made the same row produce a different destination depending on where the manager was opened.

## Deferred until there is a real need

- Do not add a scrolling list yet. The current screens use measured pagination at the logical 320x240 minimum. A scroll widget should be introduced only when a real screen exceeds the page model, with keyboard focus and controller behavior specified at the same time.
- Do not add icons, badges, animations, or new lifecycle features as visual decoration. The current action labels and row roles communicate the state without another visual language.
- Do not add a second shared screen base class. The pure geometry module plus the thin `VersionedScreen` adapter is the current deep seam. A new base class would need a concrete lifecycle or rendering responsibility first.
- Do not rewrite every locale from English by assumption. Existing locale files have parity, but a translation redesign needs native-language review and screenshots for the long-label cases. The current code already measures translated labels safely.
- Do not make the transient Download or Preparing screens persistent merely to make them easier to screenshot. Their short lifetime is part of the product flow. If future diagnostics need stable capture points, add an autotester synchronization hook rather than changing user-visible behavior.

## Follow-up receipts worth collecting later

- Run the same screenshot gallery at a second GUI scale and with the longest supported locale strings. Record the actual logical viewport and label widths before changing any geometry constants.
- Review controller/keyboard navigation with a real gamepad or focused-widget trace. Mouse screenshots do not prove focus order.
- Add a focused geometry test only if the pure layout rules change. The important invariants are row order, no overlap, centered groups, measured minimums, and a footer bottom margin.
- If the installed-pack details list grows beyond the current auxiliary rows, split actions into explicit sections before introducing scrolling. Keep lifecycle actions separate from inspection and storage actions.
- The broad release-gate run now covers the `UpdatePlanner`/`UpdateTransactionExecutor` preservation invariant when activating Pack B after Pack A has a local edit. The planner no longer emits a preservation record for a path already present in the projected final state, and the regression is covered by `UpdatePlannerTest`.
- The separate loading/waiting UX pass is intentionally not folded into this audit commit. It will research the vanilla waiting pattern and centralize delayed appearance/minimum visibility for genuinely asynchronous work.

## Current hand-off

The implementation is prepared locally on `gen/ui-history`. The audit commit is ready after the final diff/formatter review; no push is authorized yet. Review the [before gallery](autotester/out/gui-baseline-before-contact-sheet.png), [after gallery](autotester/out/gui-after-contact-sheet.png), and the branch diff first.
