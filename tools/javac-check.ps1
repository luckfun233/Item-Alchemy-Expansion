# 快速编译校验：用 Gradle 导出的 classpath 直接 javac（不跑 loom remap，秒级出结果）
#
# 前置：先用 tools/dump-classpath.init.gradle 生成项目根下的 iaexp-classpath.txt
#   .\gradlew.bat iaexpDumpCp --no-daemon --offline -I tools\dump-classpath.init.gradle
#
# 用法（任意目录）：
#   pwsh -NoProfile -File tools\javac-check.ps1
#   可选 -OutDir / -ArgFile 指定不同路径，便于并行校验
param(
    [string]$OutDir = "$env:TEMP\iaexp-javac-classes",
    [string]$ArgFile = "$env:TEMP\iaexp-javac-args.txt"
)

$proj = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$cpFile = Join-Path $proj 'iaexp-classpath.txt'
if (-not (Test-Path $cpFile)) {
    Write-Error "缺少 $cpFile；先运行： .\gradlew.bat iaexpDumpCp --no-daemon --offline -I tools\dump-classpath.init.gradle"
    exit 2
}

$cp = (Get-Content $cpFile -Raw).Trim()
if (Test-Path $OutDir) { Remove-Item $OutDir -Recurse -Force }
New-Item -ItemType Directory -Force $OutDir | Out-Null

# 注意：javac 的 @argfile 解析不接受引号包裹路径，这里全部不加引号
$srcs = Get-ChildItem (Join-Path $proj 'src') -Recurse -File -Filter *.java | ForEach-Object { $_.FullName }
Set-Content -Path $ArgFile -Encoding utf8 -Value (@(
    '-nowarn', '-proc:none', '-encoding', 'UTF-8', '--release', '17',
    '-d', $OutDir, '-cp', $cp
) + $srcs)

& javac "@$ArgFile"
$code = $LASTEXITCODE
Write-Output "javac exit=$code  sources=$($srcs.Count)"
Remove-Item $OutDir -Recurse -Force -ErrorAction SilentlyContinue
exit $code
