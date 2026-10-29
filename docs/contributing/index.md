# Contributing

The SDK is a small project with a large surface: a Java runtime, a firmware adapter, a Gradle build
plugin, a template, a Python installer tool and these docs. Contributions are welcome.

## Good places to start

- A sample plugin or a plugin recipe that shows a pattern the docs do not cover.
- A documentation fix: a broken command, a stale limit, a confusing explanation.
- A test that reproduces a bug on the fake car.
- A portability fix that keeps the car's Java 1.4 constraints intact.
- An adapter for another MHI2Q firmware build. Start with [Adding a firmware adapter](adapters.md) and
  [Stock files](stock-files.md).

## Before you change code

Read [Architecture](../explanation/architecture.md) and [Safety model](../explanation/safety-model.md).
The non-negotiables:

- plugin code never runs on a stock HMI thread;
- on-unit classes stay class-file v48;
- `runtime`, `platform` and `compat` stay Java 1.4 API;
- API changes are additive;
- the plugin class loader stays filtered;
- no stock classes outside `adapter-hqc`.

## Workflow

1. Open an issue describing the change, or comment on an existing one.
2. Branch from `main`.
3. Make the change and add tests.
4. Run the gates in [Build and test](build-and-test.md).
5. Update the docs and `CHANGELOG.md` in the same change.
6. Open a pull request.

## Licensing

By contributing you agree that your changes are licensed under Apache-2.0.

## Documentation

The docs are built from `docs/` with MkDocs Material. The API reference is generated from Javadoc with
Dokka. If you change behaviour, update the page that describes it, the reference table that lists it and
the Javadoc that contracts it.

The [docs style guide](docs-style-guide.md) explains the voice and structure.
