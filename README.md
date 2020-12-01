# Gradle central dependencies (TOML) migration script

Simple script helping with migration to a new [Gradle Central declaration](https://docs.gradle.org/nightly/userguide/platforms.html#sub:central-declaration-of-dependencies) of dependencies in TOML file.

This script was used just for my internal purposes. I made it available, just because it can be useful for somebody else. It is not the best way how to do it. It is just a dirty script of how to do it fast. The configuration is possible by variables inside of the script.

## Features
  - Generate TOML file from existing multi-module project dependencies.
  - Automatically generate `[versions]` block inside of TOML based on the same version.
  - Automatically generate alias inside of TOML (customizable).
  - Replace classic dependencies with aliases inside of TOML file.
  - Simple Groovy to Kotlin conversion based on [GradleKotlinConverter](https://github.com/bernaferrari/GradleKotlinConverter).
  - Allows converting classic dependency with included version variable into plain form (useful for Kotlin conversion).

## Requirements
 - Gradle 6.9 with TOML support. The script itself will work on Gradle 6.7+, but you need at least 6.9 to use the central dependencies mechanism.
 - You need to have dependency declaration in this format: `"com.squareup.okhttp3:okhttp:1.0.0"` or with some variable like `"com.squareup.okhttp3:okhttp:${rootProject.ext.okhttpLibVersion}"`, different declaration format are not supported.

## Usage
### Build scripts already in Kotlin
 1. Copy file *tomldeps.gradle.kts* to root of your project and put this to the end of your root *build.gradle.kts*:
    `apply(from = "tomldeps.gradle.kts")`
 2. Change the `operation` variable inside of the script to `GENERATE_TOML`.
 3. Run `gradlew`.
 4. Check generated `gradle/dependencies.toml`.
 5. If you are not satisfied with aliases you can modify `generateAlias` function inside of the script and then you can run `gradlew` again.
 6. You can do manual changes in TOML file, but don't forgot to change the operation back to `NOTHING` to avoid overridden by the next run of `gradlew`.
 7. If you are satisfied with TOML file, change `operation` to `REPLACE_DEPS_BY_TOML` a run `gradlew` again.
 8. You can now delete `apply` from your root build script and delete the script.

By default script will always create a copy of your build script, you can control it by `replaceOriginalFiles` variable.

###  Build scripts in Groovy

Steps are the same as for script in Kotlin, but you need to do a few additional things.

 1. Copy file *tomldeps.gradle.kts* to root of your project and put this to the end of your root *build.gradle.kts*:
    `apply from: 'tomldeps.gradle.kts'`
 2. Change `inputFilesName` to `build.gradle` only.
 4. Replace old `build.gradle` files with converted one `build.gradle.kts` you can do it file by file, to keep project buildable all the time. Automatic to Kotlin conversion is very simple, just basic stuff. In some cases, it can even break something. You definitely need to finish conversion manually.
 5. Continue with steps 2. to 6. of "**Build scripts already in Kotlin**".
 6. Change `inputFilesName` back to `build.gradle.kts`
 7. Continue with steps 7. and 8. of "**Build scripts already in Kotlin**".

 If you want to to do to Kotlin conversion completely manually change `doToKotlinConversion` to `false`.
