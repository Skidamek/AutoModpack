# Loading and waiting UX

Status: implemented in a separate commit after the GUI audit.

## Problem

The update flow opened `PreparingScreen` before it knew if the next step needed time. A fast local operation then replaced that screen a few milliseconds later. The result looked like a broken transition.

The same problem can occur between `PreparingScreen`, `DownloadScreen`, and the final review or restart screen.

## Research receipt

- [Nielsen Norman Group response-time guidance](https://www.nngroup.com/articles/response-times-3-important-limits/) treats less than 100 ms as instant. It recommends visible feedback when work takes longer than one second and progress details for longer work.
- [Apple loading guidance](https://developer.apple.com/design/human-interface-guidelines/loading) says that the best load finishes before the user notices. It also separates determinate progress from indeterminate activity.
- [Material progress guidance](https://m2.material.io/components/progress-indicators) says that progress indicators must communicate an active process. It recommends one consistent indicator for each type of work.
- [Vercel Web Interface Guidelines](https://vercel.com/design/guidelines) recommends a 150–300 ms show delay and a 300–500 ms minimum visible time for loading states.
- Vanilla Minecraft 1.21.11 exposes `LoadingDotsWidget` and `LoadingDotsText`. Its mapped implementation cycles `O o o`, `o O o`, `o o O`, `o O o` every 300 ms. See the [mapped widget](https://mappings.dev/1.21.11/net/minecraft/client/gui/components/LoadingDotsWidget.html).

## Decision

Use one shared loading transition for full-screen asynchronous work:

1. Wait 300 ms before showing a loading screen.
2. If the operation finishes first, show the result directly.
3. If the loading screen appears, keep it visible for at least 400 ms.
4. Use an indeterminate vanilla-style marker when the operation has no honest percentage.

The values have receipts. The 300 ms show delay matches the upper end of the Vercel range and the exact delay used by the vanilla dots. The 400 ms minimum is inside the Vercel range and matches the 300 ms/400 ms loader guidance from the [Kolibri Design System](https://design-system.learningequality.org/loaders/).

## Implementation

`LoadingTransition` is a small reusable timing component. `ScreenImpl` uses it for both `PreparingScreen` and `DownloadScreen`. The operation starts at the same time as before. Only the presentation is delayed or held.

`LoadingDots` follows the vanilla four-frame sequence. It uses elapsed time, not worker speed, so a faster computer does not make the animation unreadable.

Client storage cleanup remains in its existing screen. That screen gives immediate feedback by disabling its action and showing the operation result in context. It does not need a full-screen transition.

## Deferred

The download path already has a determinate byte and file counter. A future pass can improve its wording or add cancellation recovery if measurements show a problem. This commit does not add a new progress language or a new feature.
