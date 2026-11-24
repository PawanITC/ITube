[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$BootstrapServers,

    [string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Pure local rendering: never runs Terraform, kubectl, AWS APIs or a deployment command.
$servers = @($BootstrapServers.Split(',') | ForEach-Object { $_.Trim() })
foreach ($server in $servers) {
    if ($server -notmatch '^[A-Za-z0-9][A-Za-z0-9.-]*\.kafka\.eu-west-2\.amazonaws\.com:9098$') {
        throw 'Use the private SASL/IAM bootstrap servers from msk_bootstrap_brokers_sasl_iam (London hostnames on port 9098, comma separated).'
    }
}

# A JSON string is also a valid quoted YAML scalar; do not interpolate shell code or unescaped YAML.
$quotedServers = ConvertTo-Json -InputObject ($servers -join ',') -Compress
$template = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'configmap.yaml.template'))
$config = $template.Replace('__MSK_BOOTSTRAP_SERVERS__', $quotedServers)
$workload = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'kafka-ui.yaml'))
$manifest = $config.TrimEnd() + "`n---`n" + $workload.TrimStart()

if ($OutputPath) {
    $destination = [System.IO.Path]::GetFullPath($OutputPath)
    [System.IO.File]::WriteAllText($destination, $manifest, (New-Object System.Text.UTF8Encoding($false)))
} else {
    Write-Output $manifest
}
