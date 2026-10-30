param([switch]$TestOnly)
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
Set-Location -LiteralPath $projectRoot
if (-not $env:JAVA_HOME) { throw '请设置 JAVA_HOME 指向 JDK 17 或 21。' }
$javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
New-Item -ItemType Directory -Force -Path 'app\build\core-tests' | Out-Null
& $javac -encoding UTF-8 -d 'app\build\core-tests' 'app\src\main\java\local\asset\assistant\Ledger.java' 'tests\LedgerTest.java'
if ($LASTEXITCODE -ne 0) { throw '账本测试编译失败' }
& $java -cp 'app\build\core-tests' LedgerTest
if ($LASTEXITCODE -ne 0) { throw '账本测试失败' }
if ($TestOnly) { exit 0 }
if (Test-Path -LiteralPath '.\gradlew.bat') { & .\gradlew.bat --no-daemon :app:assembleDebug }
elseif (Get-Command gradle.bat -ErrorAction SilentlyContinue) { & gradle.bat --no-daemon :app:assembleDebug }
else { throw '请用 Android Studio 打开项目，或安装 Gradle 8.11.1 并加入 PATH。' }
if ($LASTEXITCODE -ne 0) { throw 'APK 构建失败' }
