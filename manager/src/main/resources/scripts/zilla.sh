#!/bin/sh
if [ -n "$ZILLA_INCUBATOR_ENABLED" ]; then
    JAVA_OPTIONS="$JAVA_OPTIONS -Dzilla.incubator.enabled=$ZILLA_INCUBATOR_ENABLED"
fi
ZILLA_DIRECTORY="${0%/*}"
JAVA_OPTIONS="$JAVA_OPTIONS -Dzilla.directory=$ZILLA_DIRECTORY"
exec $ZILLA_DIRECTORY/{javaBin} $JAVA_OPTIONS -m io.aklivity.zilla.runtime.command/io.aklivity.zilla.runtime.command.internal.ZillaMain "$@"