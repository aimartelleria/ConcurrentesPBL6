#!/bin/bash
# Script para generar certificados self-signed para RMI con TLS

echo "Generating self-signed certificate for RMI TLS..."
echo

# Generar keystore con certificado
keytool -genkey -alias rmi-server -keyalg RSA -keysize 2048 \
  -keystore server.keystore -validity 365 \
  -dname "CN=localhost, OU=Development, O=RMI-Cluster, L=Local, ST=Dev, C=US" \
  -storepass changeit -keypass changeit

if [ $? -eq 0 ]; then
    echo
    echo "✓ Certificate generated successfully!"
    echo
    echo "Files created:"
    echo "  - server.keystore (contains private key and certificate)"
    echo
    echo "Next steps:"
    echo "1. Run nodes with TLS:"
    echo "   java -Djavax.net.ssl.keyStore=server.keystore \\"
    echo "        -Djavax.net.ssl.keyStorePassword=changeit \\"
    echo "        -cp server/target/server-1.0-SNAPSHOT.jar cluster.ClusterNode node-1 localhost 6100 localhost:6100 localhost:6101 localhost:6102"
    echo
    echo "2. Run client with TLS:"
    echo "   java -Djavax.net.ssl.trustStore=server.keystore \\"
    echo "        -Djavax.net.ssl.trustStorePassword=changeit \\"
    echo "        -cp client/target/client-1.0-SNAPSHOT.jar cluster.Client localhost:6100 localhost:6101 localhost:6102"
    echo
else
    echo "✗ Failed to generate certificate!"
    exit 1
fi
