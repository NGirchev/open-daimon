#!/usr/bin/env bash
set -euo pipefail

# Guard staged changes against machine-specific absolute paths and Cyrillic text.
# Cyrillic is allowed only in RU locale bundles and tests.

if ! git rev-parse --git-dir >/dev/null 2>&1; then
  echo "Error: this script must run inside a git repository."
  exit 1
fi

staged_diff="$(git diff --cached --no-color --unified=0 --diff-filter=ACMR)"

if [[ -z "${staged_diff}" ]]; then
  exit 0
fi

path_pattern='(/Users/[A-Za-z0-9._-]+/|/home/[A-Za-z0-9._-]+/|[A-Za-z]:\\Users\\[A-Za-z0-9._-]+\\)'
allowed_path_pattern='(/Users/<user>/|/home/<user>/|[A-Za-z]:\\Users\\<user>\\|/Users/\$[A-Za-z_][A-Za-z0-9_]*/|/home/\$[A-Za-z_][A-Za-z0-9_]*/|[A-Za-z]:\\Users\\\$[A-Za-z_][A-Za-z0-9_]*\\)'

added_lines="$(
  printf '%s\n' "${staged_diff}" \
    | awk '
      /^\+\+\+ b\// {
        file = substr($0, 7)
        next
      }
      /^\+/ {
        print file ":" substr($0, 2)
      }
    '
)"

path_violations="$(
  printf '%s\n' "${added_lines}" \
    | grep -E "${path_pattern}" \
    | grep -Ev "${allowed_path_pattern}" || true
)"

cyrillic_violations="$(
  printf '%s\n' "${added_lines}" \
    | awk -F':' '
      {
        file = $1
        if (file ~ /_ru\.properties$/ || file ~ /\/src\/test\// || file ~ /\/src\/it\//) {
          next
        }
        print
      }
    ' \
    | perl -ne 'print if /\p{Cyrillic}/' || true
)"

if [[ -n "${path_violations}" ]]; then
  echo "ERROR: machine-specific absolute paths detected in staged changes."
  echo "Use placeholders such as /path/to/open-daimon or C:\\Users\\<user>\\..."
  echo
  printf '%s\n' "${path_violations}"
  exit 1
fi

if [[ -n "${cyrillic_violations}" ]]; then
  echo "ERROR: Cyrillic text detected in staged changes."
  echo "Use English in code/docs/comments; keep RU text only in *_ru.properties and test sources."
  echo
  printf '%s\n' "${cyrillic_violations}"
  exit 1
fi
