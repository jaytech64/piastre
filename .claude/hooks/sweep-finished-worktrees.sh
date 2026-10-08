#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Jérémy Mailhot
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Removes the worktrees and branches of work that is already in main. It runs at
# the start of every session, because a session cannot remove the worktree it is
# running in: each one clears what the ones before it left behind.
#
# A worktree goes only when nothing can be lost with it: every commit it holds is
# in main, it has no change or untracked file, it is not locked, and no process
# is running inside it. One whose branch came in through a merge commit goes at
# once. One with no commit of its own is indistinguishable from a session that
# has just started, so it goes only after a day without git activity.
#
# It never forces a removal, and it never fails the session it runs in.

set -u

IDLE_MINUTES=1440

main_dir=$(git worktree list --porcelain 2>/dev/null | sed -n '1s/^worktree //p')
[ -n "$main_dir" ] || exit 0
git -C "$main_dir" rev-parse --verify -q refs/heads/main >/dev/null || exit 0
here=$(git rev-parse --show-toplevel 2>/dev/null)

git -C "$main_dir" worktree prune
on_main=$(git -C "$main_dir" rev-list --first-parent main)

in_main() { git -C "$main_dir" merge-base --is-ancestor "$1" main 2>/dev/null; }

has_no_commit_of_its_own() { grep -qx "$1" <<<"$on_main"; }

is_clean() { [ -z "$(git -C "$1" --no-optional-locks status --porcelain 2>/dev/null)" ]; }

is_running_something() {
    local cwd
    for cwd in /proc/[0-9]*/cwd; do
        case "$(readlink "$cwd" 2>/dev/null)" in
            "$1" | "$1"/*) return 0 ;;
        esac
    done
    return 1
}

was_used_lately() {
    local git_dir
    git_dir=$(git -C "$1" rev-parse --absolute-git-dir 2>/dev/null) || return 0
    [ -n "$(find "$git_dir" -maxdepth 1 -type f -mmin "-$IDLE_MINUTES" -print -quit 2>/dev/null)" ]
}

git -C "$main_dir" worktree list --porcelain |
    awk -v RS= -F'\n' '{
        path = sha = branch = locked = ""
        for (i = 1; i <= NF; i++) {
            if ($i ~ /^worktree /) path = substr($i, 10)
            else if ($i ~ /^HEAD /) sha = substr($i, 6)
            else if ($i ~ /^branch refs\/heads\//) branch = substr($i, 19)
            else if ($i ~ /^locked/) locked = "locked"
        }
        print path "\t" sha "\t" branch "\t" locked
    }' |
    while IFS=$'\t' read -r path sha branch locked; do
        [ "$path" != "$main_dir" ] && [ "$path" != "$here" ] || continue
        [ -z "$locked" ] && [ -d "$path" ] || continue
        in_main "$sha" && is_clean "$path" || continue
        is_running_something "$path" && continue
        has_no_commit_of_its_own "$sha" && was_used_lately "$path" && continue

        git -C "$main_dir" worktree remove "$path" 2>/dev/null || continue
        echo "Removed finished worktree $path"
    done

# Ancestry is checked here rather than left to `branch -d`, which measures
# against whatever the main checkout has out, not against main.
git -C "$main_dir" for-each-ref --format='%(refname:short)%09%(objectname)%09%(worktreepath)' refs/heads |
    while IFS=$'\t' read -r branch sha checked_out_in; do
        [ "$branch" != main ] && [ -z "$checked_out_in" ] || continue
        in_main "$sha" || continue
        git -C "$main_dir" branch -D "$branch" >/dev/null 2>&1 && echo "Deleted merged branch $branch"
    done

exit 0
