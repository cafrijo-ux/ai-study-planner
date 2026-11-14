param([string]$Flutter='flutter')
$ErrorActionPreference='Stop'
$project=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if(-not (Get-Command $Flutter -ErrorAction SilentlyContinue)) {throw '현재 SDK가 필요합니다. 자동 설치하지 않습니다.'}
Push-Location -LiteralPath $project
try {
  Write-Output ('격리 빌드 대상: ' + $project)
  & $Flutter pub get
  if($LASTEXITCODE -ne 0) {throw 'Flutter dependencies failed'}
  & $Flutter --no-version-check build web --no-pub --no-web-resources-cdn --pwa-strategy=none --base-href=/ipad/
  if($LASTEXITCODE -ne 0) {throw 'Flutter 빌드 실패'}
  node tools/prepare-pwa.cjs
  if($LASTEXITCODE -ne 0) {throw 'PWA 사전 캐시 목록 생성 실패'}
} finally {Pop-Location}

