# Project Cleanup and Version Update Plan

This plan aims to resolve all warnings and errors identified in the project's build configuration files, including dependency updates, lint warning fixes, and script optimizations.

## User Review Required

> [!IMPORTANT]
> This plan involves updating many dependencies to their latest stable versions. While this improves security and performance, it can occasionally introduce breaking changes. I will perform a build to verify compatibility.

> [!NOTE]
> Unused dependency aliases in `libs.versions.toml` will be kept but their warnings will be noted. If you prefer, I can remove them entirely to keep the file "clean."

## Proposed Changes

### Build Configuration (`:app`)

#### [MODIFY] [build.gradle.kts](file:///C:/Users/DELL/Projects/Aplikasi RT/Kas-Pintar-RT004/app/build.gradle.kts)
- Fix redundant curly braces in string templates (e.g., `"${rootDir}"` -> `"$rootDir"`).
- Remove duplicate dependency declarations identified by lint.
- Verify and potentially update `targetSdk` if a newer version is available (currently targeting 36).

### Version Catalog

#### [MODIFY] [libs.versions.toml](file:///C:/Users/DELL/Projects/Aplikasi RT/Kas-Pintar-RT004/gradle/libs.versions.toml)
- Update all dependencies to the latest stable versions as identified by the analysis:
    - `androidx.core:core-ktx`: 1.18.0 -> 1.19.0
    - `androidx.lifecycle`: 2.8.7 -> 2.11.0
    - `androidx.activity:activity-compose`: 1.10.1 -> 1.13.0
    - `kotlin`: 2.2.10 -> 2.4.10
    - `compose-bom`: 2024.09.00 -> 2026.08.00
    - `navigation-compose`: 2.8.9 -> 2.9.8
    - `room`: 2.7.0 -> 2.8.4
    - ... and others as listed in the research.

## Verification Plan

### Automated Tests
- Run `./gradlew assembleDebug` to ensure the project still builds after version updates.
- Run `gradle_sync` to verify the IDE is in sync with the new configurations.

### Manual Verification
- Review the `analyze_file` output again to ensure all previously reported warnings are resolved.
