# Smile's toolb - system voice helper process
#
# Started by the mod as:
#   powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File narrator_helper.ps1
#
# Purpose: expose the offline Windows speech stack to the JVM. Two independent engines are used:
#
#   1. onecore  - WinRT Windows.Media.SpeechSynthesis. Reaches the OneCore voice store
#                 (SAPI5 cannot see it). This is the same store Windows uses for
#                 "Microsoft Huihui / Yaoyao / Kangkang".
#   2. sapi     - System.Speech (SAPI5). Reaches the classic desktop voices such as
#                 "Microsoft Huihui Desktop" / "Microsoft Zira Desktop", which OneCore
#                 does not expose.
#
# The union of both engines matches the voice list shown in Windows Settings, except for
# the Narrator-only "Natural HD" voices: those ship as data-only MSIX packages
# (MicrosoftWindows.Voice.*) whose private neural renderer is embedded in Narrator and is
# not reachable from any third-party process. They are detected and reported as a hint.
#
# No network access is performed by this script: both engines synthesise locally for every
# installed voice.
#
# Protocol (one command per line, TAB separated, free text Base64 encoded):
#   in : SPEAK <seq> <voiceIdB64> <ratePercent> <volumePercent> <textB64>
#        STOP | LIST | PING | QUIT
#   out: READY
#        ENGINE <name> <ok> <messageB64>
#        HINT <messageB64>
#        VOICE <n> <idB64> <nameB64> <gender> <language> <online> <engine>
#        VOICES_END <count> | SPOKEN <seq> | PONG | ERR <messageB64>
#
# Voice ids are prefixed with the engine that owns them: "onecore:<registry path>" or
# "sapi:<display name>". Bare ids starting with "HKEY_" are treated as onecore, which keeps
# configuration files written by older versions working.
#
# NOTE: this file is intentionally ASCII-only. Windows PowerShell 5.1 decodes .ps1 files
# without a BOM using the active ANSI code page, which would corrupt non-ASCII literals.

[CmdletBinding()]
param(
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# Output helpers
# ---------------------------------------------------------------------------

function Write-Line {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Text)
    [Console]::Out.WriteLine($Text)
    [Console]::Out.Flush()
}

function ConvertTo-B64 {
    param([AllowNull()][AllowEmptyString()][string]$Text)
    if ([string]::IsNullOrEmpty($Text)) { return '' }
    return [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Text))
}

function ConvertFrom-B64 {
    param([AllowNull()][AllowEmptyString()][string]$Text)
    if ([string]::IsNullOrEmpty($Text)) { return '' }
    try {
        return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Text))
    } catch {
        return ''
    }
}

function Clamp {
    param([int]$Value, [int]$Min, [int]$Max)
    if ($Value -lt $Min) { return $Min }
    if ($Value -gt $Max) { return $Max }
    return $Value
}

function Load-SpeechAssembly {
    param([string]$Name)
    # Add-Type only loads an assembly here (no code is compiled), but fall back to a plain
    # assembly load for hosts where Add-Type is restricted by policy.
    try {
        Add-Type -AssemblyName $Name -ErrorAction Stop
        return $true
    } catch {
        try {
            [void][System.Reflection.Assembly]::LoadWithPartialName($Name)
            return $true
        } catch {
            return $false
        }
    }
}

# ---------------------------------------------------------------------------
# Shared playback (SoundPlayer keeps STOP responsive for both engines)
# ---------------------------------------------------------------------------

$script:Player = New-Object System.Media.SoundPlayer

function Play-Buffer {
    param([Parameter(Mandatory = $true)]$Stream)
    try { $script:Player.Stop() } catch { }
    $script:Player.Stream = $Stream
    $script:Player.Load()
    # Play() is asynchronous, so the command loop keeps draining stdin (STOP stays responsive)
    $script:Player.Play()
}

# ---------------------------------------------------------------------------
# Edge online playback
#
# The Edge service only returns MP3. SoundPlayer cannot decode MP3, and the WPF
# MediaPlayer silently refuses to play in this host, so the WinRT MediaPlayer is
# used instead (verified: playback position advances). The JVM does the network
# synthesis and hands over a temporary MP3 path.
# ---------------------------------------------------------------------------

$script:EdgePlayer = $null
$script:EdgeReady = $false
$script:EdgeInfo = ''

function Initialize-EdgePlayer {
    if ($script:EdgeReady) { return }
    try {
        $playerType = [Windows.Media.Playback.MediaPlayer, Windows.Media.Playback, ContentType = WindowsRuntime]
        $script:EdgePlayer = [Activator]::CreateInstance($playerType)
        $script:EdgeReady = $true
        $script:EdgeInfo = 'ok'
    } catch {
        $script:EdgeReady = $false
        $script:EdgeInfo = 'media player init failed: ' + $_.Exception.Message
        throw $script:EdgeInfo
    }
}

function Invoke-PlayEdgeFile {
    param(
        [AllowEmptyString()][string]$Path,
        [int]$Volume
    )

    if ([string]::IsNullOrWhiteSpace($Path)) { throw 'empty audio path' }
    if (-not (Test-Path -LiteralPath $Path)) { throw ('audio file not found: ' + $Path) }

    Initialize-EdgePlayer
    # Only one thing should be audible at a time
    try { $script:Player.Stop() } catch { }

    $sourceType = [Windows.Media.Core.MediaSource, Windows.Media.Core, ContentType = WindowsRuntime]
    $script:EdgePlayer.Source = $sourceType::CreateFromUri((New-Object System.Uri -ArgumentList $Path))
    $script:EdgePlayer.Volume = (Clamp $Volume 0 100) / 100.0
    $script:EdgePlayer.Play()
}

function Stop-Audio {
    try { $script:Player.Stop() } catch { }
    if ($null -ne $script:EdgePlayer) {
        try { $script:EdgePlayer.Pause() } catch { }
        # Dropping the source releases the file so the JVM can delete it
        try { $script:EdgePlayer.Source = $null } catch { }
    }
}

# ---------------------------------------------------------------------------
# Engine 1: WinRT / OneCore
# ---------------------------------------------------------------------------

$script:WinrtReady = $false
$script:WinrtInfo = ''
$script:AsTaskGeneric = $null
$script:SynthesizerType = $null
$script:StreamType = $null
$script:Synthesizer = $null

function Initialize-Winrt {
    if (-not (Load-SpeechAssembly 'System.Runtime.WindowsRuntime')) {
        throw 'System.Runtime.WindowsRuntime could not be loaded'
    }

    $script:AsTaskGeneric = $null
    foreach ($method in [System.WindowsRuntimeSystemExtensions].GetMethods()) {
        if ($method.Name -ne 'AsTask') { continue }
        $parameters = $method.GetParameters()
        if ($parameters.Count -eq 1 -and $parameters[0].ParameterType.Name -eq 'IAsyncOperation`1') {
            $script:AsTaskGeneric = $method
            break
        }
    }
    if ($null -eq $script:AsTaskGeneric) {
        throw 'AsTask(IAsyncOperation<T>) overload not found'
    }

    $script:StreamType = [Windows.Media.SpeechSynthesis.SpeechSynthesisStream, Windows.Media.SpeechSynthesis, ContentType = WindowsRuntime]
    $script:SynthesizerType = [Windows.Media.SpeechSynthesis.SpeechSynthesizer, Windows.Media.SpeechSynthesis, ContentType = WindowsRuntime]

    $script:WinrtReady = $true
    $script:WinrtInfo = 'ok'
}

function Wait-WinRt {
    param(
        [Parameter(Mandatory = $true)]$Operation,
        [Parameter(Mandatory = $true)][Type]$ResultType
    )
    $asTask = $script:AsTaskGeneric.MakeGenericMethod($ResultType)
    $task = $asTask.Invoke($null, @($Operation))
    $task.Wait(-1) | Out-Null
    return $task.Result
}

function Get-WinrtVoiceList {
    if (-not $script:WinrtReady) { return @() }
    return ([Windows.Media.SpeechSynthesis.SpeechSynthesizer, Windows.Media.SpeechSynthesis, ContentType = WindowsRuntime])::AllVoices
}

function Get-Synthesizer {
    if (-not $script:WinrtReady) { throw 'onecore engine not available' }
    if ($null -eq $script:Synthesizer) {
        $script:Synthesizer = [Activator]::CreateInstance($script:SynthesizerType)
    }
    return $script:Synthesizer
}

function Invoke-SpeakWinrt {
    param(
        [AllowEmptyString()][string]$VoiceId,
        [int]$Rate,
        [int]$Volume,
        [AllowEmptyString()][string]$Text
    )

    if (-not $script:WinrtReady) { throw 'onecore engine not available' }

    $synthesizer = Get-Synthesizer

    if (-not [string]::IsNullOrEmpty($VoiceId)) {
        foreach ($voice in (Get-WinrtVoiceList)) {
            if ([string]$voice.Id -eq $VoiceId) {
                $synthesizer.Voice = $voice
                break
            }
        }
    }

    $language = 'zh-CN'
    if ($null -ne $synthesizer.Voice) { $language = [string]$synthesizer.Voice.Language }

    $rateText = ([double](Clamp $Rate 50 200) / 100.0).ToString('0.##', [System.Globalization.CultureInfo]::InvariantCulture)

    # prosody carries both speed and volume, so no post-processing of the PCM data is needed
    $ssml = '<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="' + $language +
            '"><prosody rate="' + $rateText + '" volume="' + (Clamp $Volume 0 100) + '">' +
            [System.Security.SecurityElement]::Escape($Text) + '</prosody></speak>'

    $stream = $null
    try {
        $stream = Wait-WinRt ($synthesizer.SynthesizeSsmlToStreamAsync($ssml)) $script:StreamType
    } catch {
        # Some voices reject SSML prosody; plain text still works, only rate/volume are lost
        $stream = Wait-WinRt ($synthesizer.SynthesizeTextToStreamAsync($Text)) $script:StreamType
    }

    $netStream = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($stream)
    $buffer = New-Object System.IO.MemoryStream
    $netStream.CopyTo($buffer)
    $netStream.Dispose()
    $buffer.Position = 0
    Play-Buffer $buffer
}

# ---------------------------------------------------------------------------
# Engine 2: SAPI5 / desktop voices
# ---------------------------------------------------------------------------

$script:SapiReady = $false
$script:SapiInfo = ''
$script:SapiSynth = $null

function Initialize-Sapi {
    if (-not (Load-SpeechAssembly 'System.Speech')) {
        throw 'System.Speech could not be loaded'
    }
    $script:SapiSynth = New-Object System.Speech.Synthesis.SpeechSynthesizer
    # Do not hold the audio device open between utterances
    $script:SapiSynth.SetOutputToNull()
    $script:SapiReady = $true
    $script:SapiInfo = 'ok'
}

function Get-SapiVoiceList {
    if (-not $script:SapiReady) { return @() }
    return $script:SapiSynth.GetInstalledVoices()
}

function Invoke-SpeakSapi {
    param(
        [AllowEmptyString()][string]$VoiceName,
        [int]$Rate,
        [int]$Volume,
        [AllowEmptyString()][string]$Text
    )

    if (-not $script:SapiReady) { throw 'sapi engine not available' }

    $synth = $script:SapiSynth
    if (-not [string]::IsNullOrEmpty($VoiceName)) {
        $synth.SelectVoice($VoiceName)
    }

    # SAPI rate is a -10..10 logarithmic step, map the percentage onto it
    $ratio = [double](Clamp $Rate 50 200) / 100.0
    $sapiRate = 0
    if ($ratio -ne 1.0) { $sapiRate = [int][Math]::Round(10.0 * [Math]::Log($ratio, 2.0)) }
    $synth.Rate = Clamp $sapiRate ([int]-10) ([int]10)
    $synth.Volume = Clamp $Volume 0 100

    $buffer = New-Object System.IO.MemoryStream
    try {
        $synth.SetOutputToWaveStream($buffer)
        $synth.Speak($Text)
    } finally {
        # SetOutputToNull flushes and detaches the stream
        try { $synth.SetOutputToNull() } catch { }
    }
    $buffer.Position = 0
    Play-Buffer $buffer
}

# ---------------------------------------------------------------------------
# Voice enumeration
# ---------------------------------------------------------------------------

function Send-Voices {
    $index = 0
    $entries = @()
    $entries += @(Get-WinrtVoiceList | ForEach-Object {
            [pscustomobject]@{
                Id       = 'onecore:' + [string]$_.Id
                Name     = [string]$_.DisplayName
                Gender   = [string]$_.Gender
                Language = [string]$_.Language
                Online   = 0
                Engine   = 'onecore'
            }
        })
    $entries += @(Get-SapiVoiceList | Where-Object { $_.Enabled } | ForEach-Object {
            $info = $_.VoiceInfo
            [pscustomobject]@{
                Id       = 'sapi:' + [string]$info.Name
                Name     = [string]$info.Name
                Gender   = [string]$info.Gender
                Language = [string]$info.Culture.Name
                Online   = 0
                Engine   = 'sapi'
            }
        })

    foreach ($entry in $entries) {
        $index++
        # Voices flagged "Online" (e.g. "Microsoft Aria Online (Natural)") need network
        # access. They are still listed, marked with online=1, and hidden by default.
        $online = $entry.Online
        if ($entry.Name -match 'Online') { $online = 1 }
        Write-Line ('VOICE' + "`t" + $index + "`t" + (ConvertTo-B64 $entry.Id) + "`t" + `
                (ConvertTo-B64 $entry.Name) + "`t" + $entry.Gender + "`t" + `
                $entry.Language + "`t" + $online + "`t" + $entry.Engine)
    }
    Write-Line ('VOICES_END' + "`t" + $index)
}

function Send-EngineStatus {
    if ($script:WinrtReady) {
        Write-Line ('ENGINE' + "`t" + 'onecore' + "`t1`t" + (ConvertTo-B64 'ok'))
    } else {
        Write-Line ('ENGINE' + "`t" + 'onecore' + "`t0`t" + (ConvertTo-B64 $script:WinrtInfo))
    }
    if ($script:SapiReady) {
        Write-Line ('ENGINE' + "`t" + 'sapi' + "`t1`t" + (ConvertTo-B64 'ok'))
    } else {
        Write-Line ('ENGINE' + "`t" + 'sapi' + "`t0`t" + (ConvertTo-B64 $script:SapiInfo))
    }
}

function Send-NaturalHint {
    # Narrator-only "Natural HD" voices are data-only MSIX packages whose neural renderer is
    # private to Narrator, so they cannot be synthesised from here. Report them instead of
    # silently omitting them.
    try {
        $packages = @(Get-AppxPackage -Name 'MicrosoftWindows.Voice.*' -ErrorAction Stop)
        if ($packages.Count -gt 0) {
            $names = ($packages | ForEach-Object { $_.Name }) -join ', '
            Write-Line ('HINT' + "`t" + (ConvertTo-B64 ('Narrator-only Natural HD voices detected (' +
                            $packages.Count + '): ' + $names +
                            '. They are reserved for Windows Narrator and cannot be used here.')))
        }
    } catch { }
}

# ---------------------------------------------------------------------------
# Command dispatch
# ---------------------------------------------------------------------------

function Invoke-Speak {
    param(
        [AllowEmptyString()][string]$VoiceId,
        [int]$Rate,
        [int]$Volume,
        [AllowEmptyString()][string]$Text
    )

    if ([string]::IsNullOrWhiteSpace($Text)) { return }

    if ($VoiceId -like 'sapi:*') {
        Invoke-SpeakSapi $VoiceId.Substring(5) $Rate $Volume $Text
        return
    }

    $oneCoreId = $VoiceId
    if ($oneCoreId -like 'onecore:*') { $oneCoreId = $oneCoreId.Substring(8) }

    if ($script:WinrtReady) {
        Invoke-SpeakWinrt $oneCoreId $Rate $Volume $Text
    } elseif ($script:SapiReady) {
        # No usable OneCore voice: let SAPI pick its default so the mod stays audible
        Invoke-SpeakSapi '' $Rate $Volume $Text
    } else {
        throw 'no speech engine available'
    }
}

# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

try { Initialize-Winrt } catch {
    $script:WinrtReady = $false
    $script:WinrtInfo = 'engine init failed: ' + $_.Exception.Message
}
try { Initialize-Sapi } catch {
    $script:SapiReady = $false
    $script:SapiInfo = 'engine init failed: ' + $_.Exception.Message
}

if ($SelfTest) {
    Write-Host 'OneCore (WinRT) engine:'
    if ($script:WinrtReady) {
        $i = 0
        foreach ($voice in (Get-WinrtVoiceList)) {
            $i++
            Write-Host ('  [' + $i + '] ' + $voice.DisplayName + ' | ' + $voice.Language + ' | ' + $voice.Gender)
        }
        if ($i -eq 0) { Write-Host '  (no voices)' }
    } else {
        Write-Host ('  FAILED: ' + $script:WinrtInfo)
    }

    Write-Host 'SAPI5 engine:'
    if ($script:SapiReady) {
        $i = 0
        foreach ($voice in (Get-SapiVoiceList)) {
            $i++
            Write-Host ('  [' + $i + '] ' + $voice.VoiceInfo.Name + ' | ' + $voice.VoiceInfo.Culture.Name +
                    ' | ' + $voice.VoiceInfo.Gender + ' | enabled=' + $voice.Enabled)
        }
        if ($i -eq 0) { Write-Host '  (no voices)' }
    } else {
        Write-Host ('  FAILED: ' + $script:SapiInfo)
    }

    Write-Host 'Speaking a test phrase with each working engine...'
    if ($script:WinrtReady) {
        try { Invoke-Speak '' 100 100 'OneCore voice test from Smile toolb.' }
        catch { Write-Host ('  onecore ERROR: ' + $_.Exception.Message) }
        Start-Sleep -Seconds 4
    }
    if ($script:SapiReady) {
        try { Invoke-Speak ('sapi:' + $script:SapiSynth.Voice.Name) 100 100 'SAPI voice test from Smile toolb.' }
        catch { Write-Host ('  sapi ERROR: ' + $_.Exception.Message) }
        Start-Sleep -Seconds 4
    }
    exit 0
}

Write-Line 'READY'
Send-EngineStatus
Send-NaturalHint
try {
    Send-Voices
} catch {
    Write-Line ('ERR' + "`t" + (ConvertTo-B64 ('voice enumeration failed: ' + $_.Exception.Message)))
}

$running = $true
while ($running) {
    $line = [Console]::In.ReadLine()
    if ($null -eq $line) { break }

    $line = $line.TrimEnd("`r")
    if ($line.Length -eq 0) { continue }

    $parts = $line.Split("`t")
    try {
        switch ($parts[0]) {
            'SPEAK' {
                $sequence = $parts[1]
                $voiceId = ConvertFrom-B64 $parts[2]
                $rate = 100
                $volume = 100
                if ($parts.Length -gt 3) { $rate = [int]$parts[3] }
                if ($parts.Length -gt 4) { $volume = [int]$parts[4] }
                $text = ''
                if ($parts.Length -gt 5) { $text = ConvertFrom-B64 $parts[5] }

                Invoke-Speak $voiceId $rate $volume $text
                Write-Line ('SPOKEN' + "`t" + $sequence)
            }
            'STOP' {
                Stop-Audio
            }
            'EDGE' {
                # EDGE <pathB64> <volume>: play an MP3 synthesised by the JVM
                $audioPath = ''
                if ($parts.Length -gt 1) { $audioPath = ConvertFrom-B64 $parts[1] }
                $edgeVolume = 100
                if ($parts.Length -gt 2) { $edgeVolume = [int]$parts[2] }
                Invoke-PlayEdgeFile $audioPath $edgeVolume
                Write-Line ('PLAYING' + "`t" + (ConvertTo-B64 $audioPath))
            }
            'LIST' {
                Send-Voices
            }
            'PING' {
                Write-Line 'PONG'
            }
            'QUIT' {
                $running = $false
            }
            default {
                Write-Line ('ERR' + "`t" + (ConvertTo-B64 ('unknown command: ' + $parts[0])))
            }
        }
    } catch {
        Write-Line ('ERR' + "`t" + (ConvertTo-B64 $_.Exception.Message))
    }
}

try { Stop-Audio } catch { }
exit 0
