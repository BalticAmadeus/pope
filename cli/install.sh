#!/usr/bin/env bash
# One-time setup: puts this directory (cli/) on PATH so the global `pope`
# command works from inside any pope-managed project, without a
# ./pope.bat prefix and without touching each project individually. Safe
# to re-run - does nothing if already set up. See README.md's
# "Per-machine setup" for why this is a *separate* script from the
# per-project pope/pope.bat, not the same thing installed differently.
#
# --check: silently exits 0 if already set up, 1 otherwise - no prompts,
# no writes, no output. Lets a caller (pope-init) decide whether it's
# even worth asking, instead of always asking and reporting "already
# done" after the fact.
set -e

CHECK_ONLY=0
[ "${1:-}" = "--check" ] && CHECK_ONLY=1

CLI_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MARKER="# added by pope's cli/install.sh"

# $SHELL can be spelled differently across invocation contexts on the
# same machine (e.g. Windows git-bash: "/bin/bash.exe" for a plain shell
# vs "/usr/bin/bash" for a login shell) - basename + strip a trailing
# .exe before matching, so this doesn't silently pick a different rc file
# (and wrongly conclude "not set up yet") depending on how it's invoked.
pick_rc_file() {
    case "$(basename "${SHELL%.exe}" 2>/dev/null)" in
        zsh) echo "$HOME/.zshrc" ;;
        bash) echo "$HOME/.bashrc" ;;
        *) echo "$HOME/.profile" ;;
    esac
}

RC_FILE="$(pick_rc_file)"

# Checked across every candidate rc file, not just the one pick_rc_file()
# guesses for *this* invocation - the shell-detection heuristic above is
# best-effort, and this way an entry written in one context is still
# found from another instead of getting duplicated.
ALREADY_SET_UP=0
for candidate in "$HOME/.bashrc" "$HOME/.zshrc" "$HOME/.profile"; do
    if [ -f "$candidate" ] && grep -qF "$CLI_DIR" "$candidate"; then
        ALREADY_SET_UP=1
        RC_FILE="$candidate"
        break
    fi
done

if [ "$CHECK_ONLY" = "1" ]; then
    [ "$ALREADY_SET_UP" = "1" ] && exit 0 || exit 1
fi

if [ "$ALREADY_SET_UP" = "1" ]; then
    echo "Already set up - $CLI_DIR is already added to PATH in $RC_FILE."
    exit 0
fi

{
    echo ""
    echo "$MARKER"
    echo "export PATH=\"$CLI_DIR:\$PATH\""
} >> "$RC_FILE"

echo "Added $CLI_DIR to PATH via $RC_FILE."
echo "Open a new terminal (or run: source \"$RC_FILE\") for it to take effect."
