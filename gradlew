#!/bin/sh
# Compatibility entry; the real Gradle workspace lives in android/.
task_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 1
exec "$task_dir/android/gradlew" -p "$task_dir/android" "$@"
