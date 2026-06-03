# --- Stage 1: Build the Maven project ---
FROM maven:3.9.7-eclipse-temurin-21 AS builder
WORKDIR /build

# Copy root pom.xml and dependency poms first to leverage Docker layer caching
COPY pom.xml .
COPY common/pom.xml common/
COPY client/pom.xml client/

# Copy source directories
COPY common/src common/src
COPY client/src client/src

# Package only the client module and its dependencies (common)
RUN mvn clean package -pl client -am -DskipTests

# --- Stage 2: Runtime image ---
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Copy the built jar
COPY --from=builder /build/client/target/client-1.0-SNAPSHOT.jar client.jar

# Default environment variables (sobre-escribibles al desplegar)
ENV KAFKA_BOOTSTRAP_SERVERS="kafka:9092"
ENV SCHEMA_REGISTRY_URL="http://schema-registry:8081"
ENV TELEMETRY_TOPIC="telemetry"
ENV CONSUMER_GROUP="telemetry_java_consumers"
ENV CASSANDRA_CONTACT_POINTS="cassandra:9042"
ENV CASSANDRA_LOCAL_DC="datacenter1"
ENV CASSANDRA_KEYSPACE="webhardmon"
# URI del NameNode HDFS para el camino batch (Parquet). Vacío = HDFS desactivado.
ENV HDFS_URI=""
ENV SEEDS=""
ENV JVM_OPTS=""

# Use a shell entrypoint to allow environment variable expansion (like $SEEDS and $JVM_OPTS)
ENTRYPOINT exec java $JVM_OPTS -cp client.jar cluster.Client $SEEDS
