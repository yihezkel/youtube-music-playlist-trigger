# Check the phone is still checking in, and raise a GitHub issue if it is not.
#
# Run by the "YTM Trigger - check phone is alive" scheduled task, hourly. Every
# other safeguard in this project runs ON the phone - the health checks, the
# failure log, the self-test alert - so a phone that is off, offline or crashed
# reports nothing at all. On 30 Aug it was off from 08:00 to 12:25 and the only
# sign was silence in the kitchen.
#
# Reads only. It never changes the phone or the sheet, and it costs nothing but
# a Firestore read.
#
# Runs only while you are logged on, because `gh` reads its token from the
# Windows credential store, which a task running as SYSTEM cannot get at.

$ErrorActionPreference = 'Stop'
$log = Join-Path $env:LOCALAPPDATA 'ytm-trigger-checkin.log'

function Write-Log($msg) {
    "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')  $msg" | Add-Content -Path $log -Encoding utf8
}

try {
    Set-Location $PSScriptRoot
    # `gh` writes its confirmations to stderr - closing an issue prints
    # "✓ Closed issue ..." there - and with ErrorActionPreference set to Stop
    # PowerShell turns any native stderr into a terminating error. That made a
    # successful recovery run report FAILED and exit 1, so the task would have
    # looked broken exactly when it had just done its job. Exit codes are what
    # this script judges by, and it checks them explicitly below.
    $ErrorActionPreference = 'Continue'
    $out = & node checkin.mjs --notify 2>&1
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    Write-Log "exit=$code"
    foreach ($line in $out) { Write-Log "   $line" }
    if ($code -eq 10) {
        Write-Log 'the phone is quiet; a GitHub issue should now be open'
    } elseif ($code -ne 0) {
        Write-Log "unexpected exit code $code"
    }
} catch {
    Write-Log "FAILED: $($_.Exception.Message)"
    exit 1
}
