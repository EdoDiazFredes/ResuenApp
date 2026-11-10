# Registra ResuenApp como reproductor de audio en el .msi ya generado, para que aparezca en
# Configuración > Aplicaciones > Aplicaciones predeterminadas y se pueda elegir ahí para FLAC, MP3, etc.
#
# jpackage solo crea las asociaciones de "Abrir con" de cada extensión; Windows 10/11 además pide
# que la app declare sus "Capabilities" y se anote en RegisteredApplications. jpackage no permite
# agregar claves de registro, así que se suman filas a la tabla Registry del .msi: se instalan y se
# desinstalan junto con ResuenApp.exe (mismo componente). Todo va en HKCU (instalación por usuario).
#
# Las extensiones salen de audioFileTypes en desktopApp/build.gradle.kts (una sola lista).
param(
    [Parameter(Mandatory = $true)][string]$Msi,
    [string]$Gradle = (Join-Path $PSScriptRoot "..\build.gradle.kts")
)

$ErrorActionPreference = "Stop"

$extensions = [regex]::Matches((Get-Content $Gradle -Raw), '"(\w+)"\s+to\s+"audio/') | ForEach-Object { $_.Groups[1].Value }
if ($extensions.Count -eq 0) { throw "No se encontraron extensiones de audio en $Gradle" }

# Igual que en brand-msi.ps1: el COM de Windows Installer se llama por reflexión.
function Call($obj, [string]$name, [object[]]$params = @()) {
    $plain = [object[]]::new($params.Count)
    for ($i = 0; $i -lt $params.Count; $i++) {
        $v = $params[$i]
        $plain[$i] = if ($v -is [psobject]) { $v.psobject.BaseObject } else { $v }
    }
    $obj.GetType().InvokeMember($name, [Reflection.BindingFlags]::InvokeMethod, $null, $obj, $plain)
}
function Rows($db, [string]$sql, [int]$columns) {
    $view = Call $db "OpenView" @($sql)
    Call $view "Execute" @() | Out-Null
    $rows = @()
    while ($true) {
        $rec = Call $view "Fetch" @()
        if ($null -eq $rec) { break }
        $row = @()
        for ($c = 1; $c -le $columns; $c++) { $row += $rec.GetType().InvokeMember("StringData", [Reflection.BindingFlags]::GetProperty, $null, $rec, @($c)) }
        $rows += , $row
    }
    Call $view "Close" @() | Out-Null
    return , $rows
}

$installer = New-Object -ComObject WindowsInstaller.Installer
$db = Call $installer "OpenDatabase" @([string](Resolve-Path $Msi).Path, [int]1)   # 1 = modo transaccional

# Componente de ResuenApp.exe (FileName puede venir como "CORTO~1.EXE|ResuenApp.exe").
$files = Rows $db "SELECT ``Component_``, ``FileName`` FROM ``File``" 2
$exe = $files | Where-Object { ($_[1] -split '\|')[-1] -ieq "ResuenApp.exe" } | Select-Object -First 1
if ($null -eq $exe) { throw "El .msi no tiene ResuenApp.exe en la tabla File" }
$component = $exe[0]

# La carpeta de instalación de jpackage se llama INSTALLDIR; si cambiara, mejor fallar.
$dirs = Rows $db "SELECT ``Directory`` FROM ``Directory``" 1
if (-not ($dirs | Where-Object { $_[0] -eq "INSTALLDIR" })) { throw "El .msi no tiene la carpeta INSTALLDIR" }

# Para revisar en el registro del CI qué asociaciones dejó jpackage.
$tableRows = Rows $db "SELECT ``Name`` FROM ``_Tables``" 1
$tables = $tableRows | ForEach-Object { $_[0] }
if ($tables -contains "Registry") {
    $existing = Rows $db "SELECT ``Root``, ``Key``, ``Name``, ``Value`` FROM ``Registry``" 4
    $existing | Where-Object { $_[0] -eq "0" } | ForEach-Object { Write-Output "jpackage: [$($_[0])] $($_[1]) | $($_[2]) = $($_[3])" }
} else {
    Call (Call $db "OpenView" @("CREATE TABLE ``Registry`` (``Registry`` CHAR(72) NOT NULL, ``Root`` SHORT NOT NULL, ``Key`` CHAR(255) NOT NULL LOCALIZABLE, ``Name`` CHAR(255) LOCALIZABLE, ``Value`` CHAR(0) LOCALIZABLE, ``Component_`` CHAR(72) NOT NULL PRIMARY KEY ``Registry``)")) "Execute" @() | Out-Null
}

$exePath = "[INSTALLDIR]ResuenApp.exe"
$progId = "ResuenApp.AudioFile"
$caps = "Software\Clients\Media\ResuenApp\Capabilities"
$entries = @(
    # Tipo de archivo propio de ResuenApp: ícono y cómo abrirlo (abre y reproduce, ver SingleInstance.kt).
    @("Software\Classes\$progId", $null, "Audio (ResuenApp)"),
    @("Software\Classes\$progId", "FriendlyTypeName", "Audio (ResuenApp)"),
    @("Software\Classes\$progId\DefaultIcon", $null, "`"$exePath`",0"),
    @("Software\Classes\$progId\shell", $null, "open"),
    @("Software\Classes\$progId\shell\open", "FriendlyAppName", "ResuenApp"),
    @("Software\Classes\$progId\shell\open\command", $null, "`"$exePath`" `"%1`""),
    # Nombre e ícono de la app en "Abrir con".
    @("Software\Classes\Applications\ResuenApp.exe", "FriendlyAppName", "ResuenApp"),
    @("Software\Classes\Applications\ResuenApp.exe\DefaultIcon", $null, "`"$exePath`",0"),
    @("Software\Classes\Applications\ResuenApp.exe\shell\open\command", $null, "`"$exePath`" `"%1`""),
    # Lo que lee Aplicaciones predeterminadas.
    @($caps, "ApplicationName", "ResuenApp"),
    @($caps, "ApplicationDescription", "Reproductor de música sin pérdida"),
    @($caps, "ApplicationIcon", "`"$exePath`",0"),
    @("Software\RegisteredApplications", "ResuenApp", $caps)
)
foreach ($ext in $extensions) {
    $entries += , @("$caps\FileAssociations", ".$ext", $progId)
    # Windows solo mira el nombre de estos valores; el dato da igual (vacío no se puede en un .msi).
    $entries += , @("Software\Classes\.$ext\OpenWithProgids", $progId, "ResuenApp")
    $entries += , @("Software\Classes\Applications\ResuenApp.exe\SupportedTypes", ".$ext", "ResuenApp")
}

# Root 1 = HKCU (la instalación es por usuario). Filas como literales SQL (sin objetos Record, que por COM dan errores de tipo). Ninguna clave ni
# valor lleva comillas simples; si alguna llegara a tenerla, mejor fallar que escribir algo roto.
$n = 0
foreach ($e in $entries) {
    $n++
    foreach ($text in $e) { if ($null -ne $text -and $text.Contains("'")) { throw "Comilla simple no soportada: $text" } }
    if ($null -eq $e[1]) {
        $sql = "INSERT INTO ``Registry`` (``Registry``, ``Root``, ``Key``, ``Value``, ``Component_``) VALUES ('ResuenAppAudio$n', 1, '$($e[0])', '$($e[2])', '$component')"
    } else {
        $sql = "INSERT INTO ``Registry`` (``Registry``, ``Root``, ``Key``, ``Name``, ``Value``, ``Component_``) VALUES ('ResuenAppAudio$n', 1, '$($e[0])', '$($e[1])', '$($e[2])', '$component')"
    }
    $view = Call $db "OpenView" @($sql)
    Call $view "Execute" @() | Out-Null
    Call $view "Close" @() | Out-Null
}
Call $db "Commit" @() | Out-Null
Write-Output "$n claves de registro agregadas al componente $component ($($extensions -join ', '))"
