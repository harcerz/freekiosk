<#
.SYNOPSIS
  Instalacja aplikacji DenTRIO na tablecie lub zegarku przez ADB.

.DESCRIPTION
  Instaluje dentrio-tablet.apk (tablet gabinetowy) lub dentrio-wear.apk
  (TicWatch / Wear OS) z tego katalogu. Obsluguje polaczenie po WiFi
  (adb connect) i jednorazowe parowanie wireless debugging (adb pair).

  Instalacja z flaga -r zachowuje dane aplikacji (konfiguracje, parowanie
  z klinika, token) - bezpieczna takze jako aktualizacja.

.EXAMPLE
  # Tablet po kablu USB (jedyne podlaczone urzadzenie):
  .\install-dentrio.ps1 -Target tablet

.EXAMPLE
  # Tablet po WiFi:
  .\install-dentrio.ps1 -Target tablet -Device 192.168.1.87:5555

.EXAMPLE
  # Zegarek - pierwsze podlaczenie (kod z ekranu zegarka:
  # Ustawienia > Dla deweloperow > Debugowanie bezprzewodowe > Sparuj):
  .\install-dentrio.ps1 -Target watch -Pair 192.168.1.101:42123 -Device 192.168.1.101:5555

.EXAMPLE
  # Zegarek - kolejne instalacje:
  .\install-dentrio.ps1 -Target watch -Device 192.168.1.101:5555
#>
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('tablet', 'watch')]
    [string]$Target,

    # Serial ADB albo adres ip[:port] (domyslny port 5555). Pomin, gdy
    # podlaczone jest dokladnie jedno urzadzenie.
    [string]$Device,

    # ip:port parowania wireless debugging (jednorazowo; kod ADB wpisujesz
    # w konsoli, wyswietla go ekran zegarka/tabletu).
    [string]$Pair,

    # Po instalacji na zegarku ustaw tarcze DenTRIO.
    [switch]$SetWatchface
)

$ErrorActionPreference = 'Stop'

# ── adb ──────────────────────────────────────────────────────────────────
$adb = (Get-Command adb -ErrorAction SilentlyContinue)?.Source
if (-not $adb) {
    $fallback = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
    if (Test-Path $fallback) { $adb = $fallback }
    else { throw 'Nie znaleziono adb (ani w PATH, ani w Android SDK platform-tools).' }
}

$apkDir = $PSScriptRoot
$apk = if ($Target -eq 'tablet') { Join-Path $apkDir 'dentrio-tablet.apk' }
       else                      { Join-Path $apkDir 'dentrio-wear.apk' }
if (-not (Test-Path $apk)) { throw "Brak pliku APK: $apk" }

# ── parowanie / polaczenie ──────────────────────────────────────────────
if ($Pair) {
    Write-Host "Parowanie z $Pair - wpisz 6-cyfrowy kod z ekranu urzadzenia:" -ForegroundColor Cyan
    & $adb pair $Pair
    if ($LASTEXITCODE -ne 0) { throw 'adb pair nie powiodlo sie.' }
}

if ($Device -match '^\d+\.\d+\.\d+\.\d+(:\d+)?$') {
    if ($Device -notmatch ':') { $Device = "${Device}:5555" }
    Write-Host "Laczenie z $Device..." -ForegroundColor Cyan
    & $adb connect $Device | Write-Host
    if ($LASTEXITCODE -ne 0) { throw "adb connect $Device nie powiodlo sie." }
}

$sel = @()
if ($Device) { $sel = @('-s', $Device) }

# Sanity: urzadzenie widoczne?
$state = & $adb @sel get-state 2>&1
if ($LASTEXITCODE -ne 0 -or "$state".Trim() -ne 'device') {
    & $adb devices | Write-Host
    throw "Urzadzenie nieosiagalne (get-state: $state). Sprawdz liste powyzej; przy kilku urzadzeniach podaj -Device."
}

# ── instalacja ───────────────────────────────────────────────────────────
Write-Host "Instaluje $(Split-Path $apk -Leaf) ($([math]::Round((Get-Item $apk).Length/1MB)) MB)..." -ForegroundColor Cyan
& $adb @sel install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'adb install nie powiodlo sie.' }

# ── kroki po instalacji ─────────────────────────────────────────────────
if ($Target -eq 'watch') {
    # Zgoda na przyszle aktualizacje z poziomu samej apki (bez ekranu
    # "zezwol na instalowanie nieznanych aplikacji" przy kazdym update).
    & $adb @sel shell appops set com.freekiosk REQUEST_INSTALL_PACKAGES allow
    Write-Host 'Nadano REQUEST_INSTALL_PACKAGES (aktualizacje z apki = 1 tap).'

    if ($SetWatchface) {
        & $adb @sel shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE `
            --es operation set-watchface `
            --ecn component com.freekiosk/com.freekiosk.wear.face.DentrioWatchFaceService | Out-Null
        Write-Host 'Ustawiono tarcze DenTRIO.'
    }
    Write-Host 'Gotowe. Kafelek dodaj recznie: przytrzymaj tarcze > kafelki > +.' -ForegroundColor Green
} else {
    # Wstan z ekranu i uruchom apke, zeby od razu zweryfikowac instalacje.
    & $adb @sel shell am start -n com.freekiosk/.MainActivity | Out-Null
    Write-Host 'Gotowe. DenTRIO uruchomione na tablecie.' -ForegroundColor Green
}
