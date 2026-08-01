$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$excludedSegments = @('.git', '.mvn', 'target', 'coverage')
$forbiddenDataExtensions = @('.csv', '.db', '.dump', '.sqlite', '.xls', '.xlsx')
$contentExtensions = @('.java', '.json', '.md', '.properties', '.ps1', '.sql', '.xml', '.yaml', '.yml')
$scanPatterns = @(
  @{ Name = 'private key material'; Pattern = 'BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY' },
  @{ Name = 'AWS access key'; Pattern = 'AKIA[0-9A-Z]{16}' },
  @{ Name = 'credential-bearing database URL'; Pattern = 'mysql://[^\s:/]+:[^\s@]+@' },
  @{ Name = 'mainland China mobile number'; Pattern = '(?<!\d)1[3-9]\d{9}(?!\d)' },
  @{ Name = 'mainland China identity number'; Pattern = '(?<!\d)\d{17}[0-9Xx](?!\d)' }
)

$files = Get-ChildItem -LiteralPath $repositoryRoot -Recurse -File | Where-Object {
  $pathSegments = $_.FullName.Substring($repositoryRoot.Length).Split([IO.Path]::DirectorySeparatorChar)
  -not ($pathSegments | Where-Object { $_ -in $excludedSegments })
}

$violations = [System.Collections.Generic.List[string]]::new()

foreach ($file in $files) {
  if ($file.Name -match '^\.env(?:\.|$)') {
    $violations.Add("tracked environment file: $($file.FullName)")
  }
  if ($file.Extension -in $forbiddenDataExtensions) {
    $violations.Add("forbidden data artifact: $($file.FullName)")
  }
  if ($file.FullName -eq $PSCommandPath -or $file.Extension -notin $contentExtensions) {
    continue
  }

  $content = Get-Content -Raw -LiteralPath $file.FullName
  foreach ($rule in $scanPatterns) {
    if ($content -match $rule.Pattern) {
      $violations.Add("$($rule.Name): $($file.FullName)")
    }
  }
}

if ($violations.Count -gt 0) {
  $violations | ForEach-Object { Write-Error $_ }
  throw 'Engineering boundary verification failed.'
}

Write-Output "Engineering boundary verification passed for $($files.Count) files."
