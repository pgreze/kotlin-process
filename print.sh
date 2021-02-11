#!/usr/bin/env sh
for arg in "$@"
do
    if [[ "$arg" == e=* ]]; then
      echo "$arg" 1>&2
    else
      echo "$arg"
    fi
done
