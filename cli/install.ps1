# One-time setup: puts this directory (cli/) on the current user's PATH
# so the global `pope` command works from inside any pope-managed
# project, without a .\pope.bat prefix and without touching each project
# individually. Safe to re-run - does nothing if already set up. See
# README.md's "Per-machine setup" for why this is a *separate* script
# from the per-project pope/pope.bat, not the same thing installed
# differently.
#
# -Check: silently exits 0 if already set up, 1 otherwise - no prompts,
# no writes, no output. Lets a caller (pope-init.bat) decide whether it's
# even worth asking, instead of always asking and reporting "already
# done" after the fact.
param(
    [switch]$Check
)

$cliDir = $PSScriptRoot
$currentUserPath = [Environment]::GetEnvironmentVariable("PATH", "User")
$entries = $currentUserPath -split ";" | Where-Object { $_ -ne "" }

$alreadyPresent = $entries | Where-Object {
    $_.TrimEnd('\') -ieq $cliDir.TrimEnd('\')
}

if ($Check) {
    if ($alreadyPresent) { exit 0 } else { exit 1 }
}

if ($alreadyPresent) {
    Write-Host "Already set up - $cliDir is already on PATH."
    exit 0
}

$newPath = if ($currentUserPath) { "$currentUserPath;$cliDir" } else { $cliDir }
[Environment]::SetEnvironmentVariable("PATH", $newPath, "User")

Write-Host "Added $cliDir to PATH."
Write-Host "Open a new terminal for it to take effect."
