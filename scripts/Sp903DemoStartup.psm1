function Get-ComposeEnvironmentVariableNames {
    param([Parameter(Mandatory = $true)][string[]]$ComposeFiles)
    $names = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($path in $ComposeFiles) {
        $content = Get-Content -LiteralPath $path -Raw
        foreach ($match in [regex]::Matches($content, '\$\{(?<name>[A-Za-z_][A-Za-z0-9_]*)')) {
            [void]$names.Add($match.Groups['name'].Value)
        }
    }
    foreach ($name in @('FRONTEND_ORIGIN', 'DEMO_ENV_SOURCE', 'DEMO_FRONTEND_PORT',
            'MYSQL_PWD', 'PGPASSWORD', 'COMPOSE_FILE', 'COMPOSE_PROJECT_NAME', 'COMPOSE_PROFILES')) {
        [void]$names.Add($name)
    }
    return @($names | Sort-Object)
}

function Invoke-WithProcessEnvironmentCleared {
    param(
        [Parameter(Mandatory = $true)][string[]]$Names,
        [Parameter(Mandatory = $true)][scriptblock]$Action
    )
    $original = @{}
    foreach ($name in $Names) {
        $original[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
    try {
        foreach ($name in $Names) {
            Remove-Item -LiteralPath ("Env:" + $name) -ErrorAction SilentlyContinue
        }
        & $Action
    } finally {
        foreach ($name in $Names) {
            if ($null -eq $original[$name]) {
                Remove-Item -LiteralPath ("Env:" + $name) -ErrorAction SilentlyContinue
            } else {
                [Environment]::SetEnvironmentVariable($name, $original[$name], 'Process')
            }
        }
    }
}

function Get-DemoVolumeSetStatus {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$ExistingVolumeNames,
        [Parameter(Mandatory = $true)][hashtable]$VolumeLabelsByName,
        [Parameter(Mandatory = $true)][string]$ExpectedProject,
        [Parameter(Mandatory = $true)][hashtable]$ExpectedVolumes
    )
    $expectedNames = @($ExpectedVolumes.Keys)
    $existing = @($expectedNames | Where-Object { $_ -in $ExistingVolumeNames })
    if ($existing.Count -eq 0) { return 'FreshStart' }
    if ($existing.Count -ne $expectedNames.Count) {
        throw 'Only part of the SP-903 Demo volume set exists; refusing to start.'
    }
    foreach ($name in $expectedNames) {
        $labels = $VolumeLabelsByName[$name]
        if (-not $labels -or $labels.Project -ne $ExpectedProject -or
                $labels.VolumeKey -notin @($ExpectedVolumes[$name])) {
            throw "Existing Demo volume '$name' has unknown Compose ownership; refusing to start."
        }
    }
    return 'Existing'
}

Export-ModuleMember -Function Get-ComposeEnvironmentVariableNames, Invoke-WithProcessEnvironmentCleared, Get-DemoVolumeSetStatus
