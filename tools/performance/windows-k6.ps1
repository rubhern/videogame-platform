[CmdletBinding()]
param(
  [ValidateSet('Install','Inspect','Run')][string]$Phase = 'Install',
  [ValidateSet('baseline','authenticated','mixed')][string]$Workload = 'baseline',
  [string]$ConfigFile, [string]$UsersFile, [string]$OutputDirectory,
  [ValidateRange(1,1800)][int]$TimeoutSeconds = 240
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$version = '2.3.0'
$zipHash = '112276d495e5741c968e2bc09ea6196099c1275bd6db9ee0875d173c7148ce43'
$root = Join-Path $env:LOCALAPPDATA "GameometroPerformance\k6\$version"
$zip = Join-Path $root 'k6.zip'
$exe = Join-Path $root "k6-v$version-windows-amd64\k6.exe"
function Save-Json($Path, $Value) {
  [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 30), (New-Object Text.UTF8Encoding($false)))
}
function Protect-Directory($Path) {
  New-Item -ItemType Directory -Force -Path $Path | Out-Null
  $acl = New-Object Security.AccessControl.DirectorySecurity
  $acl.SetAccessRuleProtection($true, $false)
  $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
  foreach ($identity in @($sid, (New-Object Security.Principal.SecurityIdentifier('S-1-5-18')))) {
    $rule = New-Object Security.AccessControl.FileSystemAccessRule($identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
    $acl.AddAccessRule($rule)
  }
  Set-Acl -LiteralPath $Path -AclObject $acl
}
New-Item -ItemType Directory -Force -Path $root | Out-Null
if (!(Test-Path -LiteralPath $zip)) {
  # Official portable release, pinned by the published SHA-256; no MSI/admin install.
  [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
  Invoke-WebRequest -UseBasicParsing -Uri "https://github.com/grafana/k6/releases/download/v$version/k6-v$version-windows-amd64.zip" -OutFile $zip
}
if ((Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant() -ne $zipHash) { throw 'Pinned k6 ZIP checksum mismatch' }
# Re-extract the verified archive rather than trusting an independently changed EXE.
Expand-Archive -LiteralPath $zip -DestinationPath $root -Force
$binaryHash = (Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash.ToLowerInvariant()
$versionFile = Join-Path $root 'version.txt'
$versionProcess = Start-Process -FilePath $exe -ArgumentList version -PassThru -Wait -NoNewWindow -RedirectStandardOutput $versionFile
$binaryVersion = (Get-Content -LiteralPath $versionFile | Out-String).Trim()
if ($versionProcess.ExitCode -ne 0 -or $binaryVersion -notmatch '^k6(?:\.exe)? v2\.3\.0 ') { throw 'Pinned native k6 version mismatch' }
$metadata = @{version=$binaryVersion;zipSha256=$zipHash;binarySha256=$binaryHash;nativeWindows=$true}
if ($Phase -eq 'Install') { $metadata | ConvertTo-Json -Compress; exit 0 }
if (!$ConfigFile -or !$OutputDirectory) { throw 'ConfigFile and OutputDirectory are required' }
if ($env:K6_INSECURE_SKIP_TLS_VERIFY -eq 'true') { throw 'TLS verification must remain enabled' }
$runId = 'native-' + [Guid]::NewGuid().ToString('N')
$run = Join-Path $env:LOCALAPPDATA "GameometroPerformance\runs\$runId"
$private = Join-Path $env:LOCALAPPDATA "GameometroPerformance\credentials\$runId"
Protect-Directory $run
Protect-Directory $private
$process = $null
$clocks = New-Object Collections.Generic.List[object]
try {
  foreach ($script in @('baseline.js','authenticated.js','mixed.js')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $script) -Destination $run
  }
  Copy-Item -LiteralPath $ConfigFile -Destination (Join-Path $run 'workload.json')
  $env:PERFORMANCE_CONFIG = Join-Path $run 'workload.json'
  $env:PERFORMANCE_RESULTS = $run
  $env:K6_NO_USAGE_REPORT = 'true'
  $env:K6_INSECURE_SKIP_TLS_VERIFY = 'false'
  if ($Workload -ne 'baseline') {
    if (!$UsersFile) { throw 'Protected synthetic-user handoff is required' }
    Copy-Item -LiteralPath $UsersFile -Destination (Join-Path $private 'users.json')
    $env:PERFORMANCE_USERS = Join-Path $private 'users.json'
  }
  Save-Json (Join-Path $run 'generator.json') $metadata
  $scriptFile = Join-Path $run "$Workload.js"
  $inputArguments = @('--include-system-env-vars=false', '--env', ('"PERFORMANCE_CONFIG=' + $env:PERFORMANCE_CONFIG + '"'),
    '--env', ('"PERFORMANCE_RESULTS=' + $env:PERFORMANCE_RESULTS + '"'))
  if ($Workload -ne 'baseline') { $inputArguments += @('--env', ('"PERFORMANCE_USERS=' + $env:PERFORMANCE_USERS + '"')) }
  if ($Phase -eq 'Inspect') {
    $inspection = Start-Process -FilePath $exe -ArgumentList (@('inspect') + $inputArguments + @(('"' + $scriptFile + '"'))) -PassThru -Wait -NoNewWindow -RedirectStandardOutput (Join-Path $run 'inspect.json') -RedirectStandardError (Join-Path $run 'inspect-error.log')
    $code = $inspection.ExitCode
  } else {
    $arguments = @('run','--quiet','--log-output','none','--out',('"json=' + (Join-Path $run 'metrics.json') + '"')) + $inputArguments + @(('"' + $scriptFile + '"'))
    $startInfo = New-Object Diagnostics.ProcessStartInfo
    $startInfo.FileName = $exe
    $startInfo.Arguments = $arguments -join ' '
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [Diagnostics.Process]::Start($startInfo)
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
    Save-Json (Join-Path $OutputDirectory 'generator-started.json') @{pid=$process.Id;wallUtc=[DateTime]::UtcNow.ToString('o')}
    $epoch = [DateTime]::Parse('1970-01-01T00:00:00Z').ToUniversalTime().Ticks
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while (!$process.HasExited) {
      $process.Refresh()
      $clocks.Add(@{wallSeconds=([DateTime]::UtcNow.Ticks-$epoch)/1e7;monotonicSeconds=[Diagnostics.Stopwatch]::GetTimestamp()/[Diagnostics.Stopwatch]::Frequency;cpuSeconds=$process.TotalProcessorTime.TotalSeconds;workingSetBytes=$process.WorkingSet64})
      if ($timer.Elapsed.TotalSeconds -gt $TimeoutSeconds) { $process.Kill(); throw 'Native generator exceeded its bounded deadline' }
      Start-Sleep -Milliseconds 250
    }
    $process.WaitForExit()
    $code = $process.ExitCode
    [IO.File]::WriteAllText((Join-Path $run 'k6.log'), $stdoutTask.GetAwaiter().GetResult())
    [IO.File]::WriteAllText((Join-Path $run 'k6-error.log'), $stderrTask.GetAwaiter().GetResult())
    if ($null -eq $code) { throw 'Native exit code unavailable; refusing to label the run successful' }
    Save-Json (Join-Path $run 'generator-clock.json') @($clocks.ToArray())
  }
  Save-Json (Join-Path $run 'generator-outcome.json') @{exitCode=$code;phase=$Phase}
  New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
  # Credentials are outside this directory; no secret, URL query or header is exported.
  foreach ($file in Get-ChildItem -LiteralPath $run -File) {
    if ($file.Extension -ne '.js' -and $file.Name -ne 'workload.json') { Copy-Item -LiteralPath $file.FullName -Destination $OutputDirectory }
  }
  if ($code -ne 0) { throw 'Native k6 failed; inspect retained status/check evidence' }
  Write-Output 'Native k6 completed; evidence copied; credential handoff removed.'
} finally {
  if ($process -and !$process.HasExited) { $process.Kill(); $process.WaitForExit() }
  if ($process) {
    Save-Json (Join-Path $run 'generator-clock.json') @($clocks.ToArray())
    if ($stdoutTask) { [IO.File]::WriteAllText((Join-Path $run 'k6.log'), $stdoutTask.GetAwaiter().GetResult()) }
    if ($stderrTask) { [IO.File]::WriteAllText((Join-Path $run 'k6-error.log'), $stderrTask.GetAwaiter().GetResult()) }
    Save-Json (Join-Path $run 'generator-outcome.json') @{exitCode=$process.ExitCode;phase=$Phase}
  }
  New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
  foreach ($file in Get-ChildItem -LiteralPath $run -File) {
    if ($file.Extension -ne '.js' -and $file.Name -ne 'workload.json') { Copy-Item -LiteralPath $file.FullName -Destination $OutputDirectory }
  }
  Remove-Item -LiteralPath $private -Recurse -Force -ErrorAction SilentlyContinue
  Remove-Item -LiteralPath $run -Recurse -Force -ErrorAction SilentlyContinue
}
