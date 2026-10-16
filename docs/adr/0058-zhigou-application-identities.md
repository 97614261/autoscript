# ADR 0058: Zhigou installation identities

## Decision

The user confirmed Studio applicationId `com.zhigou.kaifaban` and the default
independent Runner applicationId `com.zhigou.yunxingban` on 2026-10-10.
Kotlin namespaces, component class names, JNI symbols and protocol actions remain
unchanged. Explicit component launches must pair the new installation identity
with the original fully qualified class name.

## Data And Upgrade

Android treats these as new applications. Existing installations, projects,
logs and signing keys are not deleted, copied or automatically migrated.
Export project backups from the old app and import them into the new app.
System permissions must be granted separately. Previously generated Runner APKs
retain their original installation identity; newly generated APKs use the new
default and cannot update the old identity in place.
