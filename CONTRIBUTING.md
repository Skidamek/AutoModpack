# Contributing Guidelines for AutoModpack

Welcome to the AutoModpack project! We appreciate your interest in contributing to this effort to enhance the Minecraft modding experience. This document outlines the guidelines for contributing to the project. Whether you're a developer, a designer, or just someone passionate about gaming, there are various ways you can get involved.

## Ways to Contribute

### 1. Code Contributions
If you're a developer, you can contribute to the AutoModpack project by writing code that enhances its features, fixes bugs, or optimizes performance. Here's how:

1. Discuss the changes you want to make. By e.g. creating an issue.
2. Fork the repository and create a new branch for your feature or bug fix.
3. Make your code changes.
4. Build code with `./gradlew build` command.
5. Run `./gradlew formatApply`, then test your changes thoroughly to prevent regressions.
6. Submit a pull request, describing your changes and providing relevant context.

#### Code formatting

Gradle is the formatting authority:

- `./gradlew formatApply` formats authored Java, Gradle Kotlin, and text files.
- `./gradlew formatCheck` checks formatting without modifying files.

The style uses tabs displayed at four columns and a compact 160-column limit for Java and Kotlin. Stonecutter templates receive tab and whitespace normalization without parser-based reflow, which preserves their comment directives exactly. Generated files under `versions/` are never formatted.

IntelliJ IDEA reads `.editorconfig` automatically. For matching Java formatting in the IDE, install the Eclipse Code Formatter plugin and import `config/format/eclipse-java.xml`. Before committing, use the Gradle task so the result exactly matches CI.

### 2. Documentation
Clear and comprehensive documentation is essential. You can contribute by improving existing documentation or creating new guides:

1. Fork the repository and create a branch for your documentation changes.
2. Make your changes to the documentation, keeping it concise and easy to understand.
3. Ensure your changes are well-structured and contribute to a better user experience.
4. Submit a pull request, explaining the purpose of your documentation changes.

### 3. Bug Reports and Feature Requests
If you encounter a bug while using AutoModpack or have an idea for a new feature, you can contribute by submitting issues:

1. Go to the repository's "Issues" tab.
2. Check if a similar issue or feature request already exists.
3. If not, create a new issue, providing a clear description and steps to reproduce (for bugs).
4. Engage in discussions related to the issue, providing additional information if needed.

### 4. Localization
If you would like to translate AutoModpack to other languages, go ahead!

1. Fork the repository and create a new branch for your localization.
2. Open `src/main/resources/assets/automodpack/lang/`.
3. Check whether a file for your language already exists. Minecraft in-game locale codes are listed [on the wiki](https://minecraft.wiki/w/Language#Languages).
4. If it does not exist, copy `en_us.json` to a new file named with that locale code (for example `it_it.json`).
5. Translate the values. Keep every `%s` placeholder — the count must match English.
6. Submit a pull request.

Every locale must keep the same *regular* keys as `en_us.json`. Count-dependent strings are a bit different; see **Plural strings** below.

#### Plural strings

Counted copy (file counts, group counts, conflict counts) does **not** live as a single key with `"file"`/`"files"` glued on in Java. Java calls `UiFormat.plural(count, "automodpack.foo.bar")`, which picks a CLDR category for the active language and looks up `automodpack.foo.bar.<category>`, falling back to `automodpack.foo.bar.other` when that category is missing.

Categories we use:

| Suffix | When it is selected (examples) |
| --- | --- |
| `.one` | Singular: English `1`; Polish exactly `1`; Russian `1, 21, 31…` (not `11`); French `0` and `1` |
| `.few` | Slavic “2–4 except teens”: Polish/Silesian/Russian/Ukrainian `2, 3, 4, 22…` |
| `.many` | The rest of those Slavic counts: `0, 5, 11–14, 25…` |
| `.other` | Everything else, and the **required fallback** for every family |

`en_us.json` typically ships `.one` and `.other`. Chinese (`zh_cn`, `zh_tw`) and Korean only need `.other` — those languages have no cardinal agreement, so `PluralCategory` always selects `other`. Polish, Silesian, Russian, and Ukrainian should add `.few` and `.many` next to `.one` and `.other`:

```json
"automodpack.browser.summary.one": "%s plik, %s",
"automodpack.browser.summary.few": "%s pliki, %s",
"automodpack.browser.summary.many": "%s plików, %s",
"automodpack.browser.summary.other": "%s plików, %s"
```

Keep the siblings of one family next to each other in the JSON so they merge cleanly.

**`.other` on a key is not always a plural.** `automodpack.browser.content.other` is the content kind named “Other”, not a CLDR fallback. Plural families are the bases passed to `UiFormat.plural(...)` (`automodpack.browser.summary`, `automodpack.selection.metrics`, `automodpack.browser.conflicts`, `automodpack.browser.folderSummary`, `automodpack.update.groupsSelected`). Only those bases may grow extra `.one`/`.few`/`.many` keys; every locale still needs their `.other`.

Rules the locale test enforces (`autotester/tests/test_locale.py`):

- Regular keys (everything that is not a member of a `UiFormat.plural` family) must match `en_us.json` exactly.
- Each plural family must have `.other` in every locale.
- Extra CLDR suffixes on a known family are allowed, so Polish can ship `.few`/`.many` without forcing them on English or Chinese.
- `%s` placeholder counts must match English (or English `.other` for a suffix English does not ship).

When you add a **new language** whose plural rules are not English (`1` → one, else other), add a `case` to `PluralCategory` in `core/src/main/java/pl/skidam/automodpack_core/utils/PluralCategory.java`. A locale file without a case silently uses the English one/other pair. Follow [CLDR cardinal rules](https://unicode.org/reports/tr35/tr35-numbers.html#Language_Plural_Rules). Languages with no agreement (`zh`, `ja`, `ko`, `th`, `vi`) already map to `other`.

When you add a **new counted string** in Java:

1. Call `UiFormat.plural(count, "automodpack.your.key", extraArgs…)`. The count is always the first `%s`; extra args follow.
2. Add `automodpack.your.key.other` to **every** locale file.
3. Add `.one` where the language distinguishes singular, and `.few`/`.many` for Slavic locales that need them.
4. Do not format `"file"`/`"files"` (or the equivalent) in Java, and do not name an unrelated key `….other` unless it is this fallback.

Check with:

```bash
uv --project autotester run pytest autotester/tests/test_locale.py
```

### 5. Documentation
If you have a knack for writing, you can help improve the project's documentation. Clear and comprehensive documentation is crucial for users to understand how to use AutoModpack effectively.

1. Fork the repository and create a new branch for your documentation changes.
2. Edit existing documentation in the `/docs` directory or create new guides as needed.
3. Ensure your changes are clear, concise, and well-structured.
4. Submit a pull request with a description of your changes.

### 6. Spread the Word
Even if you're not a developer, you can still contribute by spreading the word about AutoModpack :)

## Code of Conduct
We expect all contributors to adhere to a code of conduct that promotes a positive and inclusive environment. Respect each other's ideas, communicate effectively, and work together to achieve the project's goals.

## Attribution
AutoModpack would not be possible without the collective efforts of numerous talented individuals. While you contribute to the project, remember that the content you download through the mod is credited to various creators.


### Thank you.
