@echo off
REM Script para ejecutar el cluster RMI con TLS automáticamente
REM Genera certificados, compila y arranca todo

setlocal enabledelayedexpansion

cd /d "%~dp0"

echo.
echo ====================================================
echo  RMI Cluster with TLS - Automated Setup
echo ====================================================
echo.

REM ============= STEP 1: Generar Certificados =============
if not exist "server.keystore" (
    echo [1/4] Generando certificados SSL...
    call generate-certs.bat
    if !ERRORLEVEL! NEQ 0 (
        echo ERROR: No se pudieron generar los certificados
        exit /b 1
    )
) else (
    echo [1/4] Certificados ya existen (server.keystore)
)

REM ============= STEP 2: Compilar =============
echo.
echo [2/4] Compilando archivos Java...
if not exist "src\cluster" (
    echo ERROR: Directorio src\cluster no encontrado
    exit /b 1
)
javac src\cluster\*.java 2>nul
if !ERRORLEVEL! NEQ 0 (
    echo ERROR: Fallo la compilación
    exit /b 1
)
echo ✓ Compilación exitosa

REM ============= STEP 3: Iniciar Nodos =============
echo.
echo [3/4] Iniciando nodos en terminales separadas...
echo.

set "JAVA_OPTS=-Djavax.net.ssl.keyStore=server.keystore -Djavax.net.ssl.keyStorePassword=changeit"

echo   → NODE-1 (puerto 6100)
start "NODE-1" cmd /k "cd /d "%~dp0" && java %JAVA_OPTS% -cp src cluster.ClusterNode node-1 localhost 6100 localhost:6100 localhost:6101 localhost:6102"
timeout /t 2 /nobreak

echo   → NODE-2 (puerto 6101)
start "NODE-2" cmd /k "cd /d "%~dp0" && java %JAVA_OPTS% -cp src cluster.ClusterNode node-2 localhost 6101 localhost:6100 localhost:6101 localhost:6102"
timeout /t 2 /nobreak

echo   → NODE-3 (puerto 6102)
start "NODE-3" cmd /k "cd /d "%~dp0" && java %JAVA_OPTS% -cp src cluster.ClusterNode node-3 localhost 6102 localhost:6100 localhost:6101 localhost:6102"
timeout /t 3 /nobreak

REM ============= STEP 4: Iniciar Cliente =============
echo.
echo [4/4] Iniciando cliente...
echo.

set "CLIENT_OPTS=-Djavax.net.ssl.trustStore=server.keystore -Djavax.net.ssl.trustStorePassword=changeit"

start "CLIENT" cmd /k "cd /d "%~dp0" && java %CLIENT_OPTS% -cp src cluster.Client localhost:6100 localhost:6101 localhost:6102"

echo.
echo ====================================================
echo ✓ SETUP COMPLETO!
echo ====================================================
echo.
echo Ventanas abiertas:
echo   - NODE-1 (localhost:6100)
echo   - NODE-2 (localhost:6101)
echo   - NODE-3 (localhost:6102)
echo   - CLIENT (ejecutando 12 compute calls)
echo.
echo La comunicación está ENCRIPTADA con TLS/SSL
echo.
echo Presiona cualquier tecla para terminar este script...
pause >nul
