# Documentation style guide

The docs are for plugin authors first and SDK contributors second. They are not an engineering log.

## Voice

- Write in the second person: **you** write a plugin, **you** install it.
- Use the active voice and the present tense: "The SDK loads the jar", not "the jar is loaded".
- Prefer short sentences and short paragraphs. One idea per paragraph.
- Explain *why* only when it changes what the reader should do. Otherwise show the code.
- Do not address an AI, an agent or a future maintainer. Public pages talk to the reader.

## Structure

Every page should answer one question and fit in one sitting.

- A page starts with a one-sentence summary of what the reader will do or learn.
- Prerequisites go near the top, in a short list.
- Put a runnable command or code sample before the long explanation.
- End with a "Next" or "Related" link, not a summary of the page.
- Move exact limits, field lists and tables to [Reference](https://complex256.github.io/mib2q-plugin-sdk/reference/).
  Guides explain the shape; reference pages state the rules.

Choose the page type deliberately:

| Type | Use it for |
|---|---|
| Tutorial | A first, complete success. Numbered steps, no optional branches. |
| Guide | A task an experienced reader dips into: "add a slider", "poll a daemon". |
| Reference | Exact names, fields, defaults, limits and errors. |
| Explanation | Architecture, trade-offs and why the SDK behaves the way it does. |

## Code

- Every command must run as written from the stated directory.
- Every code block must be complete enough to paste into the project being described.
- Use `sh` for shell blocks and label other languages.
- Never invent a task name or an API call. Check it against `build-logic/` or `api/`.
- Tutorial code blocks that mirror a sample use a `<!-- file: path -->` marker. The docs check compares
  the block with the file, so the sample and the page cannot drift apart.

## Formatting

- Use Material for MkDocs admonitions: `note`, `tip`, `warning`, `danger`, `example`.
- Use tables for reference data, not for prose.
- Use Mermaid for flows and architecture diagrams, and give the diagram alt text.
- Use repo-relative links for files in this repository, and full URLs for the published pages.

## What not to write

- Internal names: "the owner", "the bench unit", ticket numbers, plan task numbers, internal directory names.
- Machine-specific paths or IP addresses.
- Personal data: VIN, IMSI, location, names, home directory paths.
- Statements about the future as if they were shipped. Mark experiments and unshipped work.
- A wall of API surface on a getting-started page. Link to the reference instead.

## Word budgets

| Page | Target |
|---|---|
| `README.md` | under 1,000 words |
| Getting-started page | 300–700 words, plus code |
| Tutorial | under 5,000 words |
| Guide | under 1,500 words |
| Reference page | as long as the facts need, with tables |
