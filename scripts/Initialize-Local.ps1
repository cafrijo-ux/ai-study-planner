$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location -LiteralPath $root
$ptr=[IntPtr]::Zero
$previousOutputEncoding=$OutputEncoding
try {
    $auth=Join-Path $root '.local/auth.properties'
    if(Test-Path -LiteralPath $auth){throw 'Local account already exists. Existing configuration is preserved.'}
    $jar=Join-Path $root 'build/libs/ai-study-planner-0.0.1-SNAPSHOT.jar'
    if(-not(Test-Path -LiteralPath $jar)){throw 'Run .\gradlew.bat bootJar first.'}
    $user=Read-Host 'New local login name (letters, numbers, dash, underscore)'
    if($user -notmatch '^[A-Za-z0-9_-]{3,40}$'){throw 'Invalid login name.'}
    $secure=Read-Host 'New local password (12 or more characters)' -AsSecureString
    if($secure.Length -lt 12){throw 'At least 12 characters required.'}
    New-Item -ItemType Directory -Path '.local/data','.local/helper' -Force | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip=[IO.Compression.ZipFile]::OpenRead($jar)
    try {
        $entry=@($zip.Entries | Where-Object FullName -like 'BOOT-INF/lib/spring-security-crypto-*.jar')
        if($entry.Count -ne 1){throw 'Security library not found.'}
        $crypto=Join-Path $root '.local/helper/spring-security-crypto.jar'
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry[0],$crypto,$true)
    } finally {$zip.Dispose()}
    $ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    $plain=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
    $OutputEncoding=[Text.UTF8Encoding]::new($false)
    $hash=($plain | & java --class-path $crypto (Join-Path $root 'tools/HashPassword.java') | Out-String).Trim()
    if($LASTEXITCODE -ne 0 -or $hash -notmatch '^\$2[aby]\$12\$[./A-Za-z0-9]{53}$'){throw 'Password hashing failed.'}
    [IO.File]::WriteAllText($auth,"planner.auth.username=$user`nplanner.auth.password-hash=$hash`n",[Text.UTF8Encoding]::new($false))
    Write-Host 'Local account created. The password was not saved; configuration is excluded from Git.'
} finally {
    if($ptr -ne [IntPtr]::Zero){[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)}
    $plain=$null;$secure=$null;$hash=$null
    $OutputEncoding=$previousOutputEncoding
    Pop-Location
}
