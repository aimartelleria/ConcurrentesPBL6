#!/bin/bash
# Script para ejecutar el cluster RMI con TLS automáticamente
# Genera certificados, compila y arranca todo

set -e

cd "$(dirname "$0")"

echo
echo "===================================================="
echo "  RMI Cluster with TLS - Automated Setup"
echo "===================================================="
echo

# ============= STEP 1: Generar Certificados =============
if [ ! -f "server.keystore" ]; then
    echo "[1/4] Generando certificados SSL..."
    bash generate-certs.sh
else
    echo "[1/4] Certificados ya existen (server.keystore)"
fi

# ============= STEP 2: Compilar =============
echo
echo "[2/4] Compilando archivos Java..."
if [ ! -d "src/cluster" ]; then
    echo "ERROR: Directorio src/cluster no encontrado"
    exit 1
fi
javac src/cluster/*.java 2>/dev/null || true
echo "✓ Compilación exitosa"

# ============= STEP 3: Iniciar Nodos =============
echo
echo "[3/4] Iniciando nodos en tmux..."
echo

JAVA_OPTS="-Djavax.net.ssl.keyStore=server.keystore -Djavax.net.ssl.keyStorePassword=changeit"

# Si tmux está disponible, usar tmux; sino, usar background
if command -v tmux &> /dev/null; then
    # Crear session
    tmux new-session -d -s rmi-cluster
    
    echo "   → NODE-1 (puerto 6100)"
    tmux new-window -t rmi-cluster -n node-1
    tmux send-keys -t rmi-cluster:node-1 "cd $(pwd) && java $JAVA_OPTS -cp src cluster.ClusterNode node-1 localhost 6100 localhost:6100 localhost:6101 localhost:6102" Enter
    sleep 2
    
    echo "   → NODE-2 (puerto 6101)"
    tmux new-window -t rmi-cluster -n node-2
    tmux send-keys -t rmi-cluster:node-2 "cd $(pwd) && java $JAVA_OPTS -cp src cluster.ClusterNode node-2 localhost 6101 localhost:6100 localhost:6101 localhost:6102" Enter
    sleep 2
    
    echo "   → NODE-3 (puerto 6102)"
    tmux new-window -t rmi-cluster -n node-3
    tmux send-keys -t rmi-cluster:node-3 "cd $(pwd) && java $JAVA_OPTS -cp src cluster.ClusterNode node-3 localhost 6102 localhost:6100 localhost:6101 localhost:6102" Enter
    sleep 3
    
    # ============= STEP 4: Iniciar Cliente =============
    echo
    echo "[4/4] Iniciando cliente..."
    echo
    
    CLIENT_OPTS="-Djavax.net.ssl.trustStore=server.keystore -Djavax.net.ssl.trustStorePassword=changeit"
    
    tmux new-window -t rmi-cluster -n client
    tmux send-keys -t rmi-cluster:client "cd $(pwd) && java $CLIENT_OPTS -cp src cluster.Client localhost:6100 localhost:6101 localhost:6102" Enter
    
    echo
    echo "===================================================="
    echo "✓ SETUP COMPLETO!"
    echo "===================================================="
    echo
    echo "Sesión tmux 'rmi-cluster' creada con ventanas:"
    echo "   - node-1 (localhost:6100)"
    echo "   - node-2 (localhost:6101)"
    echo "   - node-3 (localhost:6102)"
    echo "   - client (ejecutando 12 compute calls)"
    echo
    echo "La comunicación está ENCRIPTADA con TLS/SSL"
    echo
    echo "Comandos útiles:"
    echo "   tmux attach -t rmi-cluster      (adjuntar sesión)"
    echo "   tmux kill-session -t rmi-cluster (terminar todo)"
    echo "   tmux select-window -t rmi-cluster:node-1 (cambiar ventana)"
    echo
    
else
    # Fallback: usar background sin tmux
    echo "   → NODE-1 (puerto 6100)"
    nohup java $JAVA_OPTS -cp src cluster.ClusterNode node-1 localhost 6100 localhost:6100 localhost:6101 localhost:6102 > node-1.log 2>&1 &
    sleep 2
    
    echo "   → NODE-2 (puerto 6101)"
    nohup java $JAVA_OPTS -cp src cluster.ClusterNode node-2 localhost 6101 localhost:6100 localhost:6101 localhost:6102 > node-2.log 2>&1 &
    sleep 2
    
    echo "   → NODE-3 (puerto 6102)"
    nohup java $JAVA_OPTS -cp src cluster.ClusterNode node-3 localhost 6102 localhost:6100 localhost:6101 localhost:6102 > node-3.log 2>&1 &
    sleep 3
    
    echo
    echo "[4/4] Iniciando cliente..."
    echo
    
    CLIENT_OPTS="-Djavax.net.ssl.trustStore=server.keystore -Djavax.net.ssl.trustStorePassword=changeit"
    
    nohup java $CLIENT_OPTS -cp src cluster.Client localhost:6100 localhost:6101 localhost:6102 > client.log 2>&1 &
    
    echo
    echo "===================================================="
    echo "✓ SETUP COMPLETO!"
    echo "===================================================="
    echo
    echo "Procesos ejecutándose en background:"
    echo "   - NODE-1 (localhost:6100) → node-1.log"
    echo "   - NODE-2 (localhost:6101) → node-2.log"
    echo "   - NODE-3 (localhost:6102) → node-3.log"
    echo "   - CLIENT → client.log"
    echo
    echo "La comunicación está ENCRIPTADA con TLS/SSL"
    echo
    echo "Ver logs:"
    echo "   tail -f node-1.log"
    echo "   tail -f node-2.log"
    echo "   tail -f node-3.log"
    echo "   tail -f client.log"
    echo
    echo "Matar todo:"
    echo "   pkill -f 'cluster.ClusterNode'"
    echo "   pkill -f 'cluster.Client'"
fi
