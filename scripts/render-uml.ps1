param(
    [string]$PlantUmlJar = $env:PLANTUML_JAR
)

$ErrorActionPreference = 'Stop'
if (-not $PlantUmlJar -or -not (Test-Path -LiteralPath $PlantUmlJar -PathType Leaf)) {
    throw '请通过 -PlantUmlJar 或 PLANTUML_JAR 指定已有的 PlantUML JAR。'
}
$umlDirectory = Join-Path $PSScriptRoot '../doc/uml'
$sources = @(Get-ChildItem -LiteralPath $umlDirectory -Filter '*.puml' | Sort-Object Name)
foreach ($format in @('svg', 'png')) {
    & java '-Djava.awt.headless=true' -jar $PlantUmlJar -charset UTF-8 "-t$format" @($sources.FullName)
    if ($LASTEXITCODE -ne 0) { throw "PlantUML $format 导出失败。" }
}
Write-Output "已导出 $($sources.Count) 份图源的 SVG 与 PNG；请打开 PNG 检查布局。"
