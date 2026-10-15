<#
    一键构建 APK 脚本（Windows PowerShell）
    ------------------------------------------------
    用途：检查 JDK / Android SDK，自动生成 local.properties，然后调用 Gradle 打包。

    用法：
        pwsh -File .\build-apk.ps1              # 打 release 包（用 debug 签名，可直接安装）
        pwsh -File .\build-apk.ps1 -Debug       # 打 debug 包
        pwsh -File .\build-apk.ps1 -Clean       # 先 clean 再打包

    注意：本脚本在项目作者的环境里**未能实际执行**（该环境命令行不可用），
          逻辑经过静态审查，但请以第一次运行时的输出为准。
#>

[CmdletBinding()]
param(
    [switch]$Debug,
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

function Write-Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "    [OK] $msg" -ForegroundColor Green }
function Write-Warn2($msg){ Write-Host "    [!]  $msg" -ForegroundColor Yellow }
function Write-Err($msg)  { Write-Host "    [X]  $msg" -ForegroundColor Red }

# ---------------------------------------------------------------- 1. JDK

Write-Step '检查 JDK'

$javaExe = $null
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $javaExe = Join-Path $env:JAVA_HOME 'bin\java.exe'
    Write-Ok "JAVA_HOME = $env:JAVA_HOME"
} else {
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd) {
        $javaExe = $cmd.Source
        Write-Warn2 "未设置 JAVA_HOME，回退到 PATH 中的 java：$javaExe"
    }
}

if (-not $javaExe) {
    Write-Err '没有找到 JDK。请安装 JDK 17 或更高版本，并设置 JAVA_HOME。'
    Write-Host @'

  安装建议（任选其一）：
    A) 装 Android Studio，它自带 JBR（JDK），路径通常是：
         C:\Program Files\Android\Android Studio\jbr
       然后设置： setx JAVA_HOME "C:\Program Files\Android\Android Studio\jbr"
    B) 装独立 JDK 17（Adoptium / Temurin / Oracle 均可），并设置 JAVA_HOME。

  设置完成后请重开一个终端再运行本脚本。
'@ -ForegroundColor Gray
    exit 1
}

$verLine = & $javaExe -version 2>&1 | Select-Object -First 1
Write-Ok "java 版本：$verLine"

# ---------------------------------------------------------------- 2. Android SDK

Write-Step '检查 Android SDK'

$sdkDir = $null
foreach ($candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
    if ($candidate -and (Test-Path $candidate)) { $sdkDir = $candidate; break }
}
if (-not $sdkDir) {
    $common = @(
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
        'C:\Android\Sdk',
        'D:\Android\Sdk',
        (Join-Path $env:USERPROFILE 'AppData\Local\Android\Sdk')
    )
    foreach ($p in $common) {
        if ($p -and (Test-Path $p)) { $sdkDir = $p; break }
    }
}

if (-not $sdkDir) {
    Write-Err '没有找到 Android SDK。'
    Write-Host @'

  安装建议：
    A) 装 Android Studio → 首次启动时它会引导你下载 SDK。
    B) 只装命令行工具：
         1) 下载 "Command line tools only" 并解压到 C:\Android\Sdk\cmdline-tools\latest
         2) 执行：
              sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
         3) 设置环境变量：
              setx ANDROID_HOME "C:\Android\Sdk"

  完成后请重开一个终端再运行本脚本。
'@ -ForegroundColor Gray
    exit 1
}

Write-Ok "SDK 路径：$sdkDir"

# 检查关键组件
$platformDir = Join-Path $sdkDir 'platforms\android-34'
$buildToolsDir = Join-Path $sdkDir 'build-tools'
if (Test-Path $platformDir) {
    Write-Ok 'platforms;android-34 已安装'
} else {
    Write-Warn2 '缺少 platforms;android-34，构建可能失败。可执行： sdkmanager "platforms;android-34"'
}
if (Test-Path $buildToolsDir) {
    $bt = Get-ChildItem $buildToolsDir -Directory | Select-Object -First 1
    if ($bt) { Write-Ok "build-tools：$($bt.Name)" }
} else {
    Write-Warn2 '缺少 build-tools。可执行： sdkmanager "build-tools;34.0.0"'
}

# 写 local.properties
$localProps = Join-Path $root 'local.properties'
$escaped = $sdkDir -replace '\\', '\\'
Set-Content -Path $localProps -Value "sdk.dir=$escaped" -Encoding ASCII
Write-Ok "已写入 local.properties： sdk.dir=$sdkDir"

# ---------------------------------------------------------------- 3. Gradle

Write-Step '准备 Gradle'

$gradleCmd = $null
$wrapper = Join-Path $root 'gradlew.bat'
if (Test-Path $wrapper) {
    $gradleCmd = $wrapper
    Write-Ok '使用项目自带的 gradlew.bat'
} else {
    $g = Get-Command gradle -ErrorAction SilentlyContinue
    if ($g) {
        $gradleCmd = $g.Source
        Write-Warn2 "项目里没有 gradlew（缺少 gradle-wrapper.jar），回退到 PATH 中的 gradle：$gradleCmd"
    }
}

if (-not $gradleCmd) {
    Write-Err '没有可用的 Gradle。'
    Write-Host @'

  三种解决办法（任选其一）：
    A) 用 Android Studio 打开本项目目录，直接点 Build → Build APK(s)。最简单，推荐。
    B) 安装 Gradle 8.7 并加入 PATH，然后重跑本脚本。
         https://gradle.org/releases/
    C) 若你已装 Gradle，可在项目根目录执行一次 `gradle wrapper` 生成 gradlew，
       之后本脚本就会自动使用项目自带的 wrapper。
'@ -ForegroundColor Gray
    exit 1
}

# ---------------------------------------------------------------- 4. 打包

Write-Step '开始构建（首次构建需要联网下载 AGP 与 Kotlin 插件，请耐心等待）'

$task = if ($Debug) { 'assembleDebug' } else { 'assembleRelease' }
if ($Clean) {
    Write-Host "    gradle clean" -ForegroundColor Gray
    & $gradleCmd clean
    if ($LASTEXITCODE -ne 0) { Write-Err 'clean 失败'; exit $LASTEXITCODE }
}

Write-Host "    gradle $task" -ForegroundColor Gray
& $gradleCmd $task
if ($LASTEXITCODE -ne 0) {
    Write-Err "构建失败（exit $LASTEXITCODE）。请把上面的报错原文发给我，我来定位。"
    exit $LASTEXITCODE
}

# ---------------------------------------------------------------- 5. 结果

Write-Step '构建产物'

$outDir = if ($Debug) { 'app\build\outputs\apk\debug' } else { 'app\build\outputs\apk\release' }
$apks = Get-ChildItem (Join-Path $root $outDir) -Filter '*.apk' -ErrorAction SilentlyContinue
if ($apks) {
    foreach ($apk in $apks) {
        $sizeMb = [math]::Round($apk.Length / 1MB, 2)
        Write-Ok "$($apk.FullName)  ($sizeMb MB)"
    }
    Write-Host @'

  安装到手机（需开启 USB 调试）：
      adb install -r "<上面的 apk 路径>"

  或者把 APK 拷到手机上直接点击安装（需允许「安装未知来源应用」）。
'@ -ForegroundColor Gray
} else {
    Write-Warn2 "构建命令成功，但在 $outDir 下没有找到 apk，请检查上面的输出。"
}
