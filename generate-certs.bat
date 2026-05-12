@echo off
REM Script para generar certificados self-signed para RMI con TLS

echo Generating self-signed certificate for RMI TLS...
echo.

REM Generar keystore con certificado
keytool -genkey -alias rmi-server -keyalg RSA -keysize 2048 ^
  -keystore server.keystore -validity 365 ^
  -dname "CN=localhost, OU=Development, O=RMI-Cluster, L=Local, ST=Dev, C=US" ^
  -storepass changeit -keypass changeit

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ✓ Certificate generated successfully!
    echo.
    echo Files created:
    echo   - server.keystore (contains private key and certificate)
    echo.
    echo Next steps:
    echo 1. Run nodes with TLS:
    echo    java -Djavax.net.ssl.keyStore=server.keystore ^
    echo         -Djavax.net.ssl.keyStorePassword=changeit ^
    echo         -cp src cluster.ClusterNode node-1 localhost 6100 localhost:6100 localhost:6101 localhost:6102
    echo.
    echo 2. Run client with TLS:
    echo    java -Djavax.net.ssl.trustStore=server.keystore ^
    echo         -Djavax.net.ssl.trustStorePassword=changeit ^
    echo         -cp src cluster.Client localhost:6100 localhost:6101 localhost:6102
    echo.
) else (
    echo ✗ Failed to generate certificate!
    exit /b 1
)
