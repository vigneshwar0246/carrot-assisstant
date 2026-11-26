$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech

$recognizer = New-Object System.Speech.Recognition.SpeechRecognitionEngine
$recognizer.InitialSilenceTimeout = [TimeSpan]::FromSeconds(30)
$recognizer.BabbleTimeout = [TimeSpan]::FromSeconds(8)
$recognizer.EndSilenceTimeout = [TimeSpan]::FromMilliseconds(350)
$recognizer.EndSilenceTimeoutAmbiguous = [TimeSpan]::FromMilliseconds(700)
$recognizer.MaxAlternates = 8
$recognizer.SetInputToDefaultAudioDevice()

$wakeWords = New-Object System.Speech.Recognition.Choices
$wakeWords.Add([string[]]@('carrot', 'hey carrot', 'jarvis', 'hey jarvis'))
$wakeBuilder = New-Object System.Speech.Recognition.GrammarBuilder
$wakeBuilder.Append($wakeWords)
$wakeGrammar = New-Object System.Speech.Recognition.Grammar($wakeBuilder)
$wakeGrammar.Name = 'WakeWords'
$recognizer.LoadGrammar($wakeGrammar)

$commands = New-Object System.Speech.Recognition.DictationGrammar
$commands.Name = 'Commands'
$recognizer.LoadGrammar($commands)

Register-ObjectEvent -InputObject $recognizer -EventName SpeechRecognized -Action {
    $result = $Event.SourceEventArgs.Result
    $text = $result.Text
    $hasWakeWord = $text -match '(?i)\b(carrot|jarvis)\b'
    if (-not $hasWakeWord) {
        $wakeAlternative = $result.Alternates | Where-Object {
            $_.Confidence -ge 0.25 -and $_.Text -match '(?i)\b(carrot|jarvis)\b'
        } | Sort-Object Confidence -Descending | Select-Object -First 1
        if ($wakeAlternative) {
            $text = $wakeAlternative.Text
            $hasWakeWord = $true
        }
    }
    $minimumConfidence = if ($hasWakeWord) { 0.28 } else { 0.42 }
    if ($result.Confidence -ge $minimumConfidence -or $hasWakeWord) {
        $bytes = [Text.Encoding]::UTF8.GetBytes($text)
        [Console]::Out.WriteLine('VOICE:' + [Convert]::ToBase64String($bytes))
        [Console]::Out.Flush()
    }
} | Out-Null

$recognizer.RecognizeAsync([System.Speech.Recognition.RecognizeMode]::Multiple)
try {
    while ($true) { Start-Sleep -Milliseconds 250 }
} finally {
    $recognizer.RecognizeAsyncCancel()
    $recognizer.Dispose()
}
