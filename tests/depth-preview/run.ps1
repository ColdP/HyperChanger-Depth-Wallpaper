$ErrorActionPreference = 'Stop'
$project = (Resolve-Path "$PSScriptRoot\..\..").Path
$output = Join-Path $project '.reverse-tmp\depth-preview-tests'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$gradleCache = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
$distribution = [regex]::Match((Get-Content (Join-Path $project 'gradle\wrapper\gradle-wrapper.properties') -Raw), 'gradle-[\d.]+-(?:bin|all)').Value
$gradleLib = Get-ChildItem -LiteralPath (Join-Path $gradleCache "wrapper\dists\$distribution") -Filter 'kotlin-compiler-embeddable-*.jar' -Recurse | Select-Object -First 1 -ExpandProperty DirectoryName
$stdlib = Get-ChildItem -LiteralPath $gradleLib -Filter 'kotlin-stdlib-*.jar' | Select-Object -First 1 -ExpandProperty FullName
$annotations = Get-ChildItem -LiteralPath $gradleLib -Filter 'annotations-*.jar' | Select-Object -First 1 -ExpandProperty FullName
& java "-Djava.io.tmpdir=$output" -cp "$gradleLib\*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$stdlib;$annotations" -d "$output\classes" "$project\app\src\main\java\io\github\btm_m\depthwallpaper\depthwallpaper\PreviewZoomTransform.kt" "$PSScriptRoot\PreviewZoomTransformTest.kt"
if ($LASTEXITCODE -ne 0) { throw 'Preview zoom checks failed to compile.' }
& java "-Djava.io.tmpdir=$output" -cp "$output\classes;$stdlib" io.github.btm_m.depthwallpaper.depthwallpaper.PreviewZoomTransformTestKt
if ($LASTEXITCODE -ne 0) { throw 'Preview zoom checks failed.' }
& java "-Djava.io.tmpdir=$output" -cp "$gradleLib\*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$stdlib;$annotations" -d "$output\matting-classes" "$project\app\src\main\java\io\github\btm_m\depthwallpaper\depthwallpaper\MattingEdges.kt" "$PSScriptRoot\MattingEdgesTest.kt"
if ($LASTEXITCODE -ne 0) { throw 'Matting edge checks failed to compile.' }
& java "-Djava.io.tmpdir=$output" -cp "$output\matting-classes;$stdlib" io.github.btm_m.depthwallpaper.depthwallpaper.MattingEdgesTestKt "$output\matting-edges.png"
if ($LASTEXITCODE -ne 0) { throw 'Matting edge checks failed.' }
