fastlane documentation
----

# Installation

Make sure you have the latest version of the Xcode command line tools installed:

```sh
xcode-select --install
```

For _fastlane_ installation instructions, see [Installing _fastlane_](https://docs.fastlane.tools/#installing-fastlane)

# Available Actions

## Android

### android validate_key

```sh
[bundle exec] fastlane android validate_key
```

Check that the service-account key can reach the Play Console

### android listing

```sh
[bundle exec] fastlane android listing
```

Upload texts, graphics and screenshots of the store listing (add publish:true to really apply them)

### android texts

```sh
[bundle exec] fastlane android texts
```

Upload only the texts of the store listing

### android screenshots

```sh
[bundle exec] fastlane android screenshots
```

Upload only the screenshots (phone and watch)

----

This README.md is auto-generated and will be re-generated every time [_fastlane_](https://fastlane.tools) is run.

More information about _fastlane_ can be found on [fastlane.tools](https://fastlane.tools).

The documentation of _fastlane_ can be found on [docs.fastlane.tools](https://docs.fastlane.tools).
