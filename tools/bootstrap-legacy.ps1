param([string]$Jdk8 = $env:DANGO_JDK8)
$ErrorActionPreference = 'Stop'
$taskRepo = Split-Path $PSScriptRoot -Parent
if (!$Jdk8) { $Jdk8 = $env:JAVA_HOME }
if (!(Test-Path -LiteralPath "$Jdk8\bin\java.exe")) { throw 'Set DANGO_JDK8 to a JDK 8 installation.' }
$taskMaven = if ($env:DANGO_MAVEN_REPO) { $env:DANGO_MAVEN_REPO } else { Join-Path $taskRepo '.gradle\maven' }
$taskJar = Join-Path $taskMaven 'net\minecraftforge\gradle\ForgeGradle-Modern\2.1\ForgeGradle-Modern-2.1.jar'
$taskPatch = Join-Path $PSScriptRoot 'forgegradle-gradle7.patch'
$taskPatchHash = (Get-FileHash -LiteralPath $taskPatch -Algorithm SHA256).Hash
$taskStamp = "$taskJar.bootstrap-sha256"
if ((Test-Path -LiteralPath $taskJar) -and (Test-Path -LiteralPath $taskStamp)) {
    $taskExpected = "$taskPatchHash|$((Get-FileHash -LiteralPath $taskJar -Algorithm SHA256).Hash)"
    if ((Get-Content -Raw -LiteralPath $taskStamp).Trim() -eq $taskExpected) {
        Write-Output 'Patched legacy build plugin is already available.'; exit 0
    }
}
$taskSource = Join-Path $taskRepo '.gradle\forgegradle-modern'
$taskCommit = '94fc6aa3bdefbfd848256c9a2e365739232ee071'
if (!(Test-Path -LiteralPath "$taskSource\.git")) {
    & git clone --no-checkout https://github.com/ManTouQAQ/ForgeGradle-Modern-2.1.git $taskSource
    if ($LASTEXITCODE) { throw 'Could not fetch the legacy build plugin source.' }
    & git -C $taskSource checkout --detach $taskCommit
    if ($LASTEXITCODE) { throw 'Could not check out the pinned legacy build plugin revision.' }
}
if ((& git -C $taskSource rev-parse HEAD).Trim() -ne $taskCommit) { throw 'Unexpected legacy build plugin revision; inspect the source cache.' }
& git -C $taskSource apply --reverse --check $taskPatch 2>$null
if ($LASTEXITCODE -ne 0) {
    & git -C $taskSource apply --check $taskPatch
    if ($LASTEXITCODE) { throw 'The Gradle 7 validation patch does not apply; inspect the source cache.' }
    & git -C $taskSource apply $taskPatch
    if ($LASTEXITCODE) { throw 'Could not apply the Gradle 7 validation patch.' }
}
$env:JAVA_HOME = $Jdk8
$taskGradle = if ($env:DANGO_GRADLE7) { $env:DANGO_GRADLE7 } else { Join-Path $taskSource 'gradlew.bat' }
Push-Location $taskSource
try {
    & $taskGradle publishToMavenLocal "-Dmaven.repo.local=$taskMaven" --no-daemon --max-workers=2 --console=plain
    if ($LASTEXITCODE) { throw 'Legacy build plugin compilation failed.' }
} finally { Pop-Location }
if (!(Test-Path -LiteralPath $taskJar) -or (Get-Item -LiteralPath $taskJar).Length -lt 100000) { throw 'Legacy build plugin artifact is missing or invalid.' }
"$taskPatchHash|$((Get-FileHash -LiteralPath $taskJar -Algorithm SHA256).Hash)" | Set-Content -Encoding UTF8 -LiteralPath $taskStamp
Write-Output 'Pinned legacy build plugin compiled successfully.'
