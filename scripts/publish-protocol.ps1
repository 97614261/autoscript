# Dot-source only. Keep keys local; the wire protocol contains HMACs, never keys.
function ConvertFrom-PublishJson {
    param([string]$Json)
    $arguments = @{ InputObject = $Json }
    # PowerShell 7.5 otherwise changes ISO timestamp strings before reserialization.
    if ((Get-Command ConvertFrom-Json).Parameters.ContainsKey('DateKind')) { $arguments.DateKind = 'String' }
    return (ConvertFrom-Json @arguments)
}

function Get-PublishDigest {
    param([byte[]]$Bytes)
    $hash = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($hash.ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant() }
    finally { $hash.Dispose() }
}

function Get-PublishMac {
    param([string]$Token, [string[]]$Parts)
    if ($Token -cnotmatch '^[a-f0-9]{64}$') { throw 'INVALID_PRIVATE_PUBLISH_TOKEN' }
    $hmac = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::ASCII.GetBytes($Token))
    try { return ([BitConverter]::ToString($hmac.ComputeHash([Text.Encoding]::ASCII.GetBytes(($Parts -join "`n") + "`n")))).Replace('-', '').ToLowerInvariant() }
    finally { $hmac.Dispose() }
}

function New-PublishNonce {
    $bytes = New-Object byte[] 16
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes); return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant() }
    finally { $random.Dispose() }
}

function Test-PublishMac {
    param([string]$Actual, [string]$Expected)
    if ($Actual -cnotmatch '^[a-f0-9]{64}$' -or $Expected -cnotmatch '^[a-f0-9]{64}$') { return $false }
    $difference = 0
    for ($index = 0; $index -lt 64; $index++) { $difference = $difference -bor ([int][char]$Actual[$index] -bxor [int][char]$Expected[$index]) }
    return $difference -eq 0
}

function Get-PublishEndpoint {
    param([string]$Endpoint)
    $uri = $null
    if (-not [Uri]::TryCreate($Endpoint, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -notin @('http', 'https') -or $uri.UserInfo -or $uri.Query -or $uri.Fragment -or
        $uri.AbsolutePath -cne '/api/publisher/v1/releases' -or
        $Endpoint -cne ($uri.GetLeftPart([UriPartial]::Authority) + '/api/publisher/v1/releases')) {
        throw 'INVALID_PUBLISH_ENDPOINT; use http(s)://host/api/publisher/v1/releases without credentials, query or fragment'
    }
    return $uri.AbsoluteUri
}

function Read-PublishConfiguration {
    param([string]$Path)
    if ((Get-Item -LiteralPath $Path).Length -gt 16384) { throw 'PRIVATE_CONFIG_TOO_LARGE' }
    $settings = ConvertFrom-PublishJson (Get-Content -LiteralPath $Path -Raw -Encoding UTF8)
    if (-not $settings -or -not $settings.PSObject.Properties['endpoint'] -or -not $settings.PSObject.Properties['tokenFile'] -or
        @($settings.PSObject.Properties | Where-Object { $_.Name -notin @('endpoint', 'tokenFile') }).Count -ne 0) {
        throw 'PUBLISH_CONFIG_REQUIRES_ENDPOINT; replace old SSH settings with endpoint and tokenFile'
    }
    $endpoint = Get-PublishEndpoint ([string]$settings.endpoint)
    $tokenPath = (Resolve-Path -LiteralPath $settings.tokenFile).Path
    if ((Get-Item -LiteralPath $tokenPath).Length -gt 128) { throw 'PRIVATE_TOKEN_TOO_LARGE' }
    $token = [IO.File]::ReadAllText($tokenPath).Trim()
    if ($token -cnotmatch '^[a-f0-9]{64}$') { throw 'INVALID_PRIVATE_PUBLISH_TOKEN' }
    return [pscustomobject]@{ Endpoint = $endpoint; Token = $token }
}

function New-PublishContext {
    param([string]$Token, [string]$Method, [string]$Path, [string]$Nonce, [string]$Challenge,
        [string]$ContentType = '', [long]$Length = 0, [string]$Metadata = '', [string]$Revision = '',
        [string]$BodySha = 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855')
    $lengthText = $Length.ToString([Globalization.CultureInfo]::InvariantCulture)
    $parts = @('zhigou-publish-request-v1', $Method, $Path, $Nonce, $Challenge, $ContentType, $lengthText,
        (Get-PublishDigest ([Text.Encoding]::ASCII.GetBytes($Metadata))), $Revision, $BodySha)
    $signature = Get-PublishMac -Token $Token -Parts $parts
    return [pscustomobject]@{ Method = $Method; Path = $Path; Nonce = $Nonce; Challenge = $Challenge;
        Signature = $signature; ContentType = $ContentType; Length = $Length; Metadata = $Metadata; Revision = $Revision; BodySha = $BodySha }
}

function Read-PublishResponse {
    param([Net.WebResponse]$Response, [object]$Context, [string]$Token)
    if ($Response.ContentType -notmatch '^application/json(?:;|$)' -or $Response.ContentLength -gt 65536) { throw 'PUBLISH_RESPONSE_INVALID' }
    $inputStream = $Response.GetResponseStream()
    $buffer = New-Object byte[] 4096
    $memory = New-Object IO.MemoryStream
    try {
        while (($count = $inputStream.Read($buffer, 0, $buffer.Length)) -gt 0) {
            if ($memory.Length + $count -gt 65536) { throw 'PUBLISH_RESPONSE_TOO_LARGE' }
            $memory.Write($buffer, 0, $count)
        }
        $bytes = $memory.ToArray()
        $expected = Get-PublishMac -Token $Token -Parts @('zhigou-publish-response-v1', $Context.Method,
            $Context.Path, $Context.Nonce, $Context.Signature, ([int]$Response.StatusCode).ToString([Globalization.CultureInfo]::InvariantCulture), (Get-PublishDigest $bytes))
        if (-not (Test-PublishMac -Actual $Response.Headers['X-Publish-Signature'] -Expected $expected)) {
            throw 'PUBLISH_RESPONSE_SIGNATURE_INVALID; response was not trusted; inspect the release before retrying'
        }
        return (ConvertFrom-PublishJson ([Text.UTF8Encoding]::new($false, $true).GetString($bytes)))
    } finally { $inputStream.Dispose(); $memory.Dispose() }
}

function Invoke-PublishRequest {
    param([string]$Endpoint, [string]$Token, [object]$Context, [string]$File = '', [byte[]]$Body = $null)
    $request = [Net.HttpWebRequest]::Create($Endpoint)
    $request.AllowAutoRedirect = $false
    $request.UseDefaultCredentials = $false
    $request.KeepAlive = $false
    $request.Timeout = 660000
    $request.ReadWriteTimeout = 15000
    $request.Method = $Context.Method
    $request.Headers['X-Publish-Nonce'] = $Context.Nonce
    $request.Headers['X-Publish-Challenge'] = $Context.Challenge
    $request.Headers['X-Publish-Signature'] = $Context.Signature
    $request.Headers['X-Publish-Body-Sha256'] = $Context.BodySha
    try {
        if ($File -or $null -ne $Body) {
            $request.ContentType = $Context.ContentType
            $request.Headers['X-Release-Metadata'] = $Context.Metadata
            $request.Headers['If-Match'] = $Context.Revision
            $request.AllowWriteStreamBuffering = $false
            $request.ServicePoint.Expect100Continue = $false
        }
        if ($File) {
            $inputStream = [IO.File]::OpenRead($File)
            try {
                if ($inputStream.Length -ne $Context.Length) { throw 'FROZEN_UPLOAD_SIZE_CHANGED' }
                $request.ContentLength = $Context.Length
                $output = $request.GetRequestStream()
                try {
                    $buffer = New-Object byte[] (1024 * 1024)
                    $sent = 0L
                    while (($count = $inputStream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                        $output.Write($buffer, 0, $count); $sent += $count
                        Write-Progress -Activity 'Uploading verified Studio APK' -Status ('{0:N1} / {1:N1} MiB' -f ($sent / 1MB), ($inputStream.Length / 1MB)) -PercentComplete ([int](100 * $sent / $inputStream.Length))
                    }
                } finally { $output.Dispose(); Write-Progress -Activity 'Uploading verified Studio APK' -Completed }
            } finally { $inputStream.Dispose() }
        } elseif ($null -ne $Body) {
            if ($Body.Length -ne $Context.Length) { throw 'ARCHIVE_BODY_SIZE_CHANGED' }
            $request.ContentLength = $Body.Length
            $output = $request.GetRequestStream()
            try { $output.Write($Body, 0, $Body.Length) } finally { $output.Dispose() }
        }
        $response = $request.GetResponse()
        try { return (Read-PublishResponse -Response $response -Context $Context -Token $Token) } finally { $response.Dispose() }
    } catch [Net.WebException] {
        $failureResponse = $_.Exception.Response
        if ($failureResponse) {
            try {
                $failure = Read-PublishResponse -Response $failureResponse -Context $Context -Token $Token
                if ($failure.PSObject.Properties['code'] -and $failure.code -cmatch '^[A-Z_]{1,64}$') { throw ('Publish rejected: ' + $failure.code) }
            } finally { $failureResponse.Dispose() }
        }
        throw 'PUBLISH_CONNECTION_FAILED; no automatic retry was made; inspect the release before retrying'
    } finally { $request.Abort() }
}

function Invoke-SignedPublishApi {
    param([string]$Endpoint, [string]$Token, [string]$File = '', [string]$Metadata = '', [string]$Revision = '',
        [string]$BodySha = 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', [long]$Length = 0,
        [switch]$Archives, [string[]]$ArchiveSha256 = @())
    $endpointUri = [Uri](Get-PublishEndpoint $Endpoint)
    $body = $null
    if (($Archives -and $File) -or ($ArchiveSha256.Count -gt 0 -and -not $Archives)) { throw 'INVALID_PUBLISH_OPERATION' }
    if ($ArchiveSha256.Count -gt 0) {
        if ($ArchiveSha256.Count -gt 8 -or @($ArchiveSha256 | Where-Object { $_ -cnotmatch '^[a-f0-9]{64}$' }).Count -gt 0 -or
            @($ArchiveSha256 | Select-Object -Unique).Count -ne $ArchiveSha256.Count -or $Revision -cnotmatch '^"[a-f0-9]{64}"$') { throw 'INVALID_ARCHIVE_SELECTION_OR_REVISION' }
        $body = [Text.Encoding]::UTF8.GetBytes((@{ formatVersion = 1; sha256 = @($ArchiveSha256) } | ConvertTo-Json -Compress))
    }
    $nonce = New-PublishNonce
    $context = New-PublishContext -Token $Token -Method GET -Path '/api/publisher/v1/challenge' -Nonce $nonce -Challenge '-'
    $ticket = Invoke-PublishRequest -Endpoint ($endpointUri.GetLeftPart([UriPartial]::Authority) + $context.Path) -Token $Token -Context $context
    if ($ticket.protocol -ne 1 -or $ticket.nonce -cne $nonce -or $ticket.challenge -cnotmatch '^[a-f0-9]{64}$' -or $ticket.validForSeconds -ne 120) { throw 'PUBLISH_CHALLENGE_INVALID' }
    $arguments = @{ Token = $Token; Method = 'GET'; Path = '/api/publisher/v1/releases'; Nonce = $nonce; Challenge = $ticket.challenge }
    if ($Archives) { $arguments.Path = '/api/publisher/v1/archives' }
    if ($null -ne $body) {
        $arguments.Method = 'POST'; $arguments.ContentType = 'application/json'; $arguments.Length = $body.Length
        $arguments.Revision = $Revision; $arguments.BodySha = Get-PublishDigest $body
    }
    if ($File) {
        $arguments.Method = 'POST'; $arguments.ContentType = 'application/vnd.android.package-archive'
        $arguments.Length = $Length; $arguments.Metadata = $Metadata; $arguments.Revision = $Revision; $arguments.BodySha = $BodySha
    }
    $context = New-PublishContext @arguments
    $target = $endpointUri.GetLeftPart([UriPartial]::Authority) + $context.Path
    return (Invoke-PublishRequest -Endpoint $target -Token $Token -Context $context -File $File -Body $body)
}
