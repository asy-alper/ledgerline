param([ValidateSet('run', 'test', 'verify', 'migrate', 'build')][string]$Action = 'run')
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

# Parse KEY=VALUE as data; never execute the contents of .env as PowerShell.
if (Test-Path -LiteralPath '.env') {
    foreach ($line in Get-Content -LiteralPath '.env' -Encoding UTF8) {
        $entry = $line.Trim()
        if (-not $entry -or $entry.StartsWith('#')) { continue }
        $pair = $entry.Split('=', 2)
        if ($pair.Count -ne 2 -or $pair[0] -notmatch '^[A-Z][A-Z0-9_]*$') { throw 'Invalid .env entry' }
        $value = $pair[1].Trim()
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        if ($null -eq [Environment]::GetEnvironmentVariable($pair[0])) {
            [Environment]::SetEnvironmentVariable($pair[0], $value, 'Process')
        }
    }
}
if ($Action -in @('run', 'migrate')) {
    foreach ($key in @('DATABASE_URL', 'DATABASE_USER', 'DATABASE_PASSWORD')) {
        $configured = [Environment]::GetEnvironmentVariable($key)
        if ([string]::IsNullOrWhiteSpace($configured) -or $configured.StartsWith('CHANGE_ME')) {
            throw "Set $key in .env or environment variables first; see README.md."
        }
    }
    if ($env:BOOTSTRAP_ADMIN_PASSWORD -like 'CHANGE_ME*') { throw 'Set BOOTSTRAP_ADMIN_PASSWORD in .env first.' }
}
if (-not $env:JAVA_HOME) {
    $compiler = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($compiler) {
        $candidate = Split-Path (Split-Path $compiler.Source -Parent) -Parent
        if (Test-Path -LiteralPath (Join-Path $candidate 'bin\java.exe')) { $env:JAVA_HOME = $candidate }
    }
}
switch ($Action) {
    'test' { & .\mvnw.cmd -B -ntp test; exit $LASTEXITCODE }
    'verify' { & .\mvnw.cmd -B -ntp -Pintegration verify; exit $LASTEXITCODE }
    'build' { & .\mvnw.cmd -B -ntp package; exit $LASTEXITCODE }
}
& .\mvnw.cmd -B -ntp package
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$taskJava = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java.exe' }
if ($Action -eq 'migrate') { & $taskJava '-Dfile.encoding=UTF-8' -jar target\ledgerline.jar migrate }
else { & $taskJava '-Dfile.encoding=UTF-8' -jar target\ledgerline.jar }
exit $LASTEXITCODE
