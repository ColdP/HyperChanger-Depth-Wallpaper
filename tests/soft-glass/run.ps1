$ErrorActionPreference = 'Stop'
$project = (Resolve-Path "$PSScriptRoot\..\..").Path
$output = Join-Path $project '.reverse-tmp\soft-glass-tests'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$gradleCache = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
$distribution = [regex]::Match((Get-Content (Join-Path $project 'gradle\wrapper\gradle-wrapper.properties') -Raw), 'gradle-[\d.]+-(?:bin|all)').Value
$gradleLib = Get-ChildItem -LiteralPath (Join-Path $gradleCache "wrapper\dists\$distribution") -Filter 'kotlin-compiler-embeddable-*.jar' -Recurse | Select-Object -First 1 -ExpandProperty DirectoryName
if (!$gradleLib) { throw 'Run the project Gradle wrapper first to download the Kotlin compiler.' }
$stdlib = Get-ChildItem -LiteralPath $gradleLib -Filter 'kotlin-stdlib-*.jar' | Select-Object -First 1 -ExpandProperty FullName
$annotations = Get-ChildItem -LiteralPath $gradleLib -Filter 'annotations-*.jar' | Select-Object -First 1 -ExpandProperty FullName
$env:TEMP = $output
$env:TMP = $output
$json = Join-Path $output 'json-20240303.jar'
if (!(Test-Path -LiteralPath $json)) {
    Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar' -OutFile $json
}
if ((Get-FileHash -LiteralPath $json -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED') {
    throw 'Unexpected JSON test dependency checksum.'
}
$zxing = Get-ChildItem -LiteralPath (Join-Path $gradleCache 'caches\modules-2\files-2.1\com.google.zxing\core') -Filter 'core-*.jar' -Recurse | Select-Object -First 1 -ExpandProperty FullName
if (!$zxing) { throw 'The existing app ZXing dependency is required for QR checks.' }
$sdk = $env:ANDROID_HOME
if (!$sdk) {
    $sdkLine = Get-Content (Join-Path $project 'local.properties') | Where-Object { $_ -match '^sdk.dir=' }
    $sdk = $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\')
}
$android = Get-ChildItem -LiteralPath (Join-Path $sdk 'platforms') -Filter 'android.jar' -Recurse | Select-Object -Last 1 -ExpandProperty FullName
Add-Type -AssemblyName System.IO.Compression.FileSystem
$serviceAar = Get-ChildItem -LiteralPath (Join-Path $gradleCache 'caches\modules-2\files-2.1\io.github.libxposed\service') -Filter '*.aar' -Recurse | Select-Object -First 1 -ExpandProperty FullName
$serviceJar = Join-Path $output 'xposed-service.jar'
$archive = [System.IO.Compression.ZipFile]::OpenRead($serviceAar)
try { [System.IO.Compression.ZipFileExtensions]::ExtractToFile($archive.GetEntry('classes.jar'), $serviceJar, $true) } finally { $archive.Dispose() }
$testClasspath = "$stdlib;$annotations;$json;$zxing;$android;$serviceJar"
& java "-Djava.io.tmpdir=$output" -cp "$gradleLib\*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath $testClasspath -d "$output\classes" "$project\app\src\main\java\io\github\btm_m\depthwallpaper\SoftGlassParameters.kt" "$project\app\src\main\java\io\github\btm_m\depthwallpaper\SoftGlassPresets.kt" "$project\app\src\main\java\io\github\btm_m\depthwallpaper\PresetQrPayload.kt" "$project\app\src\main\java\io\github\btm_m\depthwallpaper\SoftGlassSettings.kt" "$project\app\src\main\java\io\github\btm_m\depthwallpaper\SoftGlassRefractionBlurSettings.kt" "$project\app\src\main\java\io\github\btm_m\depthwallpaper\VolumePanelGlassSettings.kt" "$PSScriptRoot\SoftGlassParametersTest.kt" "$PSScriptRoot\SoftGlassSettingsStoreTest.kt"
if ($LASTEXITCODE -ne 0) { throw 'Kotlin regression checks failed to compile.' }
& java "-Djava.io.tmpdir=$output" -cp "$output\classes;$testClasspath" io.github.btm_m.depthwallpaper.SoftGlassParametersTestKt
if ($LASTEXITCODE -ne 0) { throw 'Soft glass regression checks failed.' }
