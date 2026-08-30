# Check the sheet for change guidance and raise a GitHub issue if there is any.
#
# Run by the "YTM Trigger - check schedule guidance" scheduled task. Deliberately
# does no AI work and changes nothing: it reads the yellow "Change guidance from
# us" columns and, if they hold anything, opens one GitHub issue so the guidance
# is not sitting unnoticed. Reworking the schedule is a judgement call and stays
# something you ask for in a Copilot CLI session.
#
# Runs only while you are logged on, because `gh` reads its token from the
# Windows credential store, which a task running as SYSTEM cannot get at.

$ErrorActionPreference = 'Stop'
$tools = Join-Path $PSScriptRoot ''
$log = Join-Path $env:LOCALAPPDATA 'ytm-trigger-guidance.log'

function Write-Log($msg) {
    "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')  $msg" | Add-Content -Path $log -Encoding utf8
}

try {
    Set-Location $tools
    # `gh` writes its confirmations to stderr, and with ErrorActionPreference
    # set to Stop PowerShell turns native stderr into a terminating error - so a
    # run that successfully raised an issue could report FAILED. Exit codes are
    # what this judges by, and it checks them explicitly below.
    $ErrorActionPreference = 'Continue'
    $out = & node guidance.mjs --notify 2>&1
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    Write-Log "exit=$code"
    foreach ($line in $out) { Write-Log "   $line" }
    if ($code -eq 10) {
        Write-Log 'guidance is pending; a GitHub issue should now be open'
    } elseif ($code -ne 0) {
        Write-Log "unexpected exit code $code"
    }
} catch {
    Write-Log "FAILED: $($_.Exception.Message)"
    exit 1
}
