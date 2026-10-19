function Get-ComposeEnvironmentVariableNames {
    param([Parameter(Mandatory = $true)][string[]]$ComposeFiles)
    $names = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($path in $ComposeFiles) {
        $content = Get-Content -LiteralPath $path -Raw
        foreach ($match in [regex]::Matches($content, '\$\{(?<name>[A-Za-z_][A-Za-z0-9_]*)')) {
            [void]$names.Add($match.Groups['name'].Value)
        }
    }
    foreach ($name in @('DB_USERNAME', 'DB_PASSWORD', 'MYSQL_ROOT_PASSWORD', 'SEMANTIC_DB_USERNAME',
            'SEMANTIC_DB_PASSWORD', 'JWT_SECRET', 'DEMO_ACCOUNT_PASSWORD', 'FRONTEND_ORIGIN',
            'DEMO_FRONTEND_PORT', 'MYSQL_PWD', 'PGPASSWORD', 'COMPOSE_FILE', 'COMPOSE_PROJECT_NAME',
            'COMPOSE_PROFILES')) {
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
    foreach ($name in $Names) { $original[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
    try {
        foreach ($name in $Names) { Remove-Item -LiteralPath ("Env:" + $name) -ErrorAction SilentlyContinue }
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
    foreach ($actualName in $ExistingVolumeNames) {
        foreach ($expectedName in $expectedNames) {
            if ([string]::Equals([string]$actualName, [string]$expectedName, [StringComparison]::OrdinalIgnoreCase) -and
                    -not [string]::Equals([string]$actualName, [string]$expectedName, [StringComparison]::Ordinal)) {
                throw 'A case-mismatched Demo volume name exists; refusing to treat it as a fresh install.'
            }
        }
    }
    $existing = @($expectedNames | Where-Object {
            $expectedName = $_
            @($ExistingVolumeNames | Where-Object { [string]::Equals([string]$_, [string]$expectedName, [StringComparison]::Ordinal) }).Count -gt 0
        })
    if ($existing.Count -eq 0) { return 'FreshStart' }
    if ($existing.Count -ne $expectedNames.Count) { throw 'Only part of the SP-903 Demo volume set exists; refusing to start.' }
    foreach ($name in $expectedNames) {
        $labels = $VolumeLabelsByName[$name]
        $validKey = $false
        foreach ($key in @($ExpectedVolumes[$name])) {
            if ($labels -and [string]::Equals([string]$labels.VolumeKey, [string]$key, [StringComparison]::Ordinal)) { $validKey = $true }
        }
        if (-not $labels -or -not [string]::Equals([string]$labels.Project, $ExpectedProject, [StringComparison]::Ordinal) -or -not $validKey) {
            throw "Existing Demo volume '$name' has unknown Compose ownership; refusing to start."
        }
    }
    return 'Existing'
}

function Normalize-DemoHostPath {
    param([Parameter(Mandatory = $true)][string]$Path)
    if ($Path.StartsWith('\\?\UNC\', [StringComparison]::OrdinalIgnoreCase)) { return ('\\' + $Path.Substring(8)).Replace('/', '\').TrimEnd('\') }
    if ($Path.StartsWith('\\?\', [StringComparison]::OrdinalIgnoreCase)) { $Path = $Path.Substring(4) }
    if ($Path -match '^[A-Za-z]:[\\/]') {
        $normalized = $Path.Replace('/', '\')
        if ($normalized.Length -eq 3) { return $normalized }
        return $normalized.TrimEnd('\')
    }
    if ($Path.StartsWith('\\')) { return $Path.Replace('/', '\').TrimEnd('\') }
    if ($Path.StartsWith('/')) {
        $normalized = [regex]::Replace($Path, '/+', '/')
        if ($normalized.Length -eq 1) { return $normalized }
        return $normalized.TrimEnd('/')
    }
    return $null
}

function Test-OrdinalEqual([string]$Left, [string]$Right) {
    return [string]::Equals($Left, $Right, [StringComparison]::Ordinal)
}

function Test-DemoHostPathMatch {
    param([Parameter(Mandatory = $true)][string]$Expected, [Parameter(Mandatory = $true)][string]$Actual)
    $expectedPath = Normalize-DemoHostPath $Expected
    if (-not $expectedPath) { return $false }
    if ($expectedPath -match '^([A-Za-z]):\\(?<tail>.*)$') {
        $desktopPath = $null
        if ($Actual -match '^/run/desktop/mnt/host/(?<drive>[A-Za-z])/(?<tail>.*)$' -or
                $Actual -match '^/host_mnt/(?<drive>[A-Za-z])/(?<tail>.*)$') {
            $desktopPath = "$($Matches.drive):\$($Matches.tail.Replace('/', '\'))"
        }
        $actualPath = if ($desktopPath) { $desktopPath } else { Normalize-DemoHostPath $Actual }
        return $actualPath -and [string]::Equals($expectedPath, $actualPath, [StringComparison]::OrdinalIgnoreCase)
    }
    $actualPath = Normalize-DemoHostPath $Actual
    if (-not $actualPath) { return $false }
    $comparison = if ($expectedPath.StartsWith('\\', [StringComparison]::Ordinal)) {
        [StringComparison]::OrdinalIgnoreCase
    } else { [StringComparison]::Ordinal }
    return [string]::Equals($expectedPath, $actualPath, $comparison)
}

function Test-DemoContainerMountContract {
    param(
        [Parameter(Mandatory = $true)]$Container,
        [Parameter(Mandatory = $true)][string]$ExpectedProject,
        [Parameter(Mandatory = $true)][string]$ExpectedService,
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [Parameter(Mandatory = $true)][hashtable]$VolumeMetadataByName,
        [switch]$AllowKnownLegacyDocumentMount,
        [switch]$AllowKnownLegacyCredentialMount,
        [switch]$AllowKnownLegacyVerifierMount
    )
    $labels = $Container.Config.Labels
    if (-not (Test-OrdinalEqual ([string]$labels.'com.docker.compose.project') $ExpectedProject) -or
            -not (Test-OrdinalEqual ([string]$labels.'com.docker.compose.service') $ExpectedService)) {
        return [pscustomobject]@{ Valid = $false; Legacy = $false; LegacyCredential = $false; LegacyVerifier = $false; Error = 'Container project or service label mismatch.' }
    }
    if ($ExpectedService -notin @('mysql', 'pgvector', 'backend', 'frontend')) {
        return [pscustomobject]@{ Valid = $false; Legacy = $false; LegacyCredential = $false; LegacyVerifier = $false; Error = 'Unknown Compose service.' }
    }

    $expected = [System.Collections.Generic.List[hashtable]]::new()
    switch ($ExpectedService) {
        'mysql' { $expected.Add(@{ Type = 'volume'; Name = 'supplypilot-sp903-local-demo-mysql'; Target = '/var/lib/mysql'; ReadOnly = $false; Source = $null; VolumeKey = 'sp903-mysql-data' }) }
        'pgvector' { $expected.Add(@{ Type = 'volume'; Name = 'supplypilot-sp903-local-demo-pgvector'; Target = '/var/lib/postgresql/data'; ReadOnly = $false; Source = $null; VolumeKey = 'sp903-pgvector-data' }) }
        'backend' {
            $expected.AddRange([hashtable[]]@(
                @{ Type = 'volume'; Name = 'supplypilot-sp903-local-demo-documents'; Target = '/app/data/demo-documents'; ReadOnly = $false; Source = $null; VolumeKey = 'sp903-document-storage' },
                @{ Type = 'bind'; Name = $null; Target = '/app/samples/demo-documents'; ReadOnly = $true; Source = (Join-Path $RepositoryRoot 'samples/documents'); VolumeKey = $null }
            ))
        }
    }

    $actual = @($Container.Mounts)
    $legacyDocument = $null
    $legacyCredential = $null
    $legacyVerifier = $null
    if ($ExpectedService -eq 'backend') {
        $legacySpecs = @(
            @{ Enabled = [bool]$AllowKnownLegacyDocumentMount; Destination = '/app/data/documents'; Type = 'volume'; Source = $null; Key = 'document-storage'; Property = 'document' },
            @{ Enabled = [bool]$AllowKnownLegacyCredentialMount; Destination = '/run/supplypilot-demo-credentials'; Type = 'bind'; Source = (Join-Path $RepositoryRoot '.sp903-demo-credential'); Key = $null; Property = 'credential' },
            @{ Enabled = [bool]$AllowKnownLegacyVerifierMount; Destination = '/run/supplypilot-demo-verifier'; Type = 'bind'; Source = (Join-Path $RepositoryRoot '.sp903-demo-verifier-client'); Key = $null; Property = 'verifier' }
        )
        foreach ($spec in $legacySpecs) {
            $matches = @($actual | Where-Object { Test-OrdinalEqual ([string]$_.Destination) $spec.Destination })
            if ($matches.Count -gt 1 -or ($matches.Count -eq 1 -and -not $spec.Enabled)) {
                return [pscustomobject]@{ Valid = $false; Legacy = $false; LegacyCredential = $false; LegacyVerifier = $false; Error = "Unexpected or duplicate legacy mount at $($spec.Destination)." }
            }
            if ($matches.Count -eq 0) { continue }
            $mount = $matches[0]
            if (-not (Test-OrdinalEqual ([string]$mount.Type) $spec.Type) -or [bool]$mount.RW -ne ($spec.Type -eq 'volume')) {
                return [pscustomobject]@{ Valid = $false; Legacy = $false; LegacyCredential = $false; LegacyVerifier = $false; Error = "Legacy mount type or access mode mismatch at $($spec.Destination)." }
            }
            if ($spec.Type -eq 'bind') {
                if (-not (Test-DemoHostPathMatch -Expected $spec.Source -Actual ([string]$mount.Source))) {
                    return [pscustomobject]@{ Valid = $false; Legacy = $false; LegacyCredential = $false; LegacyVerifier = $false; Error = "Legacy bind source mismatch at $($spec.Destination)." }
                }
            } else {
                $metadata = $VolumeMetadataByName[[string]$mount.Name]
                if (-not $metadata -or -not (Test-OrdinalEqual ([string]$metadata.Project) $ExpectedProject) -or
                        -not (Test-OrdinalEqual ([string]$metadata.VolumeKey) $spec.Key) -or
                        -not (Test-DemoHostPathMatch -Expected ([string]$metadata.Mountpoint) -Actual ([string]$mount.Source))) {
                    return [pscustomobject]@{ Valid = $false; Legacy = $false; LegacyCredential = $false; LegacyVerifier = $false; Error = 'Legacy document volume identity or mount metadata mismatch.' }
                }
            }
            switch ($spec.Property) {
                'document' { $legacyDocument = $mount }
                'credential' { $legacyCredential = $mount }
                'verifier' { $legacyVerifier = $mount }
            }
        }
    }

    $legacyCount = [int]($null -ne $legacyDocument) + [int]($null -ne $legacyCredential) + [int]($null -ne $legacyVerifier)
    if ($actual.Count -ne $expected.Count + $legacyCount) {
        return [pscustomobject]@{ Valid = $false; Legacy = $false; LegacyCredential = $false; LegacyVerifier = $false; Error = "Unexpected or missing container mounts (actual=$($actual.Count), expected=$($expected.Count))." }
    }
    foreach ($contract in $expected) {
        $matches = @($actual | Where-Object {
                (Test-OrdinalEqual ([string]$_.Type) ([string]$contract.Type)) -and
                (Test-OrdinalEqual ([string]$_.Destination) ([string]$contract.Target)) -and
                ($contract.Type -eq 'bind' -or (Test-OrdinalEqual ([string]$_.Name) ([string]$contract.Name)))
            })
        if ($matches.Count -ne 1) {
            return [pscustomobject]@{ Valid = $false; Legacy = $false; LegacyCredential = $false; LegacyVerifier = $false; Error = "Missing or duplicate mount at $($contract.Target)." }
        }
        $mount = $matches[0]
        if ([bool]$mount.RW -eq [bool]$contract.ReadOnly) {
            return [pscustomobject]@{ Valid = $false; Legacy = $false; LegacyCredential = $false; LegacyVerifier = $false; Error = "Incorrect write access at $($contract.Target)." }
        }
        if ($contract.Type -eq 'bind') {
            if (-not (Test-DemoHostPathMatch -Expected $contract.Source -Actual ([string]$mount.Source))) {
                return [pscustomobject]@{ Valid = $false; Legacy = $false; LegacyCredential = $false; LegacyVerifier = $false; Error = "Unexpected bind source at $($contract.Target)." }
            }
        } else {
            $metadata = $VolumeMetadataByName[[string]$mount.Name]
            if (-not $metadata -or -not (Test-OrdinalEqual ([string]$metadata.Project) $ExpectedProject) -or
                    -not (Test-OrdinalEqual ([string]$metadata.VolumeKey) ([string]$contract.VolumeKey)) -or
                    -not (Test-DemoHostPathMatch -Expected ([string]$metadata.Mountpoint) -Actual ([string]$mount.Source))) {
                return [pscustomobject]@{ Valid = $false; Legacy = $false; LegacyCredential = $false; LegacyVerifier = $false; Error = "Unexpected named volume metadata at $($contract.Target)." }
            }
        }
    }
    return [pscustomobject]@{ Valid = $true; Legacy = ($null -ne $legacyDocument); LegacyCredential = ($null -ne $legacyCredential); LegacyVerifier = ($null -ne $legacyVerifier); Error = $null }
}

function Invoke-DemoBackendMountUpgrade {
    param(
        [Parameter(Mandatory = $true)][scriptblock]$GetDatabaseContainerIds,
        [Parameter(Mandatory = $true)][scriptblock]$RecreateBackend,
        [Parameter(Mandatory = $true)][scriptblock]$AssertStrictMounts
    )
    $before = @(& $GetDatabaseContainerIds)
    if ($before.Count -ne 2 -or @($before | Where-Object { [string]::IsNullOrWhiteSpace([string]$_) }).Count -ne 0) {
        throw 'Could not capture both database container IDs before the Backend upgrade.'
    }
    & $RecreateBackend
    $after = @(& $GetDatabaseContainerIds)
    if ($after.Count -ne 2 -or -not [string]::Equals([string]$before[0], [string]$after[0], [StringComparison]::Ordinal) -or
            -not [string]::Equals([string]$before[1], [string]$after[1], [StringComparison]::Ordinal)) {
        throw 'Database container IDs changed during the Backend-only legacy upgrade.'
    }
    & $AssertStrictMounts
}

Export-ModuleMember -Function Get-ComposeEnvironmentVariableNames, Invoke-WithProcessEnvironmentCleared, Get-DemoVolumeSetStatus, Normalize-DemoHostPath, Test-DemoHostPathMatch, Test-DemoContainerMountContract, Invoke-DemoBackendMountUpgrade
