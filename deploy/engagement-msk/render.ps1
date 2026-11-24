[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$BootstrapServers,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Image,

    [string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$servers = @($BootstrapServers.Split(',') | ForEach-Object { $_.Trim() })
foreach ($server in $servers) {
    if ($server -notmatch '^[A-Za-z0-9][A-Za-z0-9.-]*\.kafka\.eu-west-2\.amazonaws\.com:9098$') {
        throw 'Use the private London SASL/IAM bootstrap servers from msk_bootstrap_brokers_sasl_iam (port 9098, comma separated).'
    }
}
# Require a release tag or digest for a rebuilt image that contains the MSK authentication plugin.
if ($Image -notmatch '^[a-z0-9][a-z0-9._:/-]*(?::[A-Za-z0-9_][A-Za-z0-9_.-]*|@sha256:[a-f0-9]{64})$' -or $Image -match ':latest$') {
    throw 'Pass a rebuilt Engagement image with an explicit release tag or sha256 digest (not latest).'
}

# Render locally using the current base, without editing it or including other services.
# Keep the tiny temporary input directory for inspection; nothing calls a cluster or an AWS API.
$work = Join-Path ([System.IO.Path]::GetTempPath()) ('itube-engagement-msk-' + [guid]::NewGuid().ToString('N'))
[System.IO.Directory]::CreateDirectory($work) | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding($false)
$base = Join-Path $PSScriptRoot '../k8s/engagement.yaml'
[System.IO.File]::Copy([System.IO.Path]::GetFullPath($base), (Join-Path $work 'engagement.yaml'))
[System.IO.File]::Copy((Join-Path $PSScriptRoot 'serviceaccount.yaml'), (Join-Path $work 'serviceaccount.yaml'))

# JSON quoting is valid YAML scalar quoting; never insert unescaped input into shell commands/YAML.
$config = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'configmap.yaml.template'))
$config = $config.Replace('__MSK_BOOTSTRAP_SERVERS__', (ConvertTo-Json -InputObject ($servers -join ',') -Compress))
[System.IO.File]::WriteAllText((Join-Path $work 'configmap.yaml'), $config, $utf8)
$patch = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'engagement.patch.yaml.template'))
$patch = $patch.Replace('__ENGAGEMENT_IMAGE__', (ConvertTo-Json -InputObject $Image -Compress))
[System.IO.File]::WriteAllText((Join-Path $work 'engagement.patch.yaml'), $patch, $utf8)
$kustomization = @'
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: itube
resources:
  - engagement.yaml
  - serviceaccount.yaml
  - configmap.yaml
patches:
  - path: engagement.patch.yaml
'@
[System.IO.File]::WriteAllText((Join-Path $work 'kustomization.yaml'), $kustomization, $utf8)
$rendered = & kubectl kustomize $work
if ($LASTEXITCODE -ne 0) { throw 'Client-side Kustomize rendering failed.' }
$manifest = ($rendered -join "`n") + "`n"

if ($OutputPath) {
    $destination = [System.IO.Path]::GetFullPath($OutputPath)
    [System.IO.File]::WriteAllText($destination, $manifest, $utf8)
} else {
    Write-Output $manifest
}
