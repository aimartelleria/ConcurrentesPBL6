# --- Stage 1: Build the Maven project ---
FROM maven:3.9.7-eclipse-temurin-21 AS builder
WORKDIR /build

# Copy root pom.xml and dependency poms first to leverage Docker layer caching
COPY pom.xml .
COPY common/pom.xml common/
COPY server/pom.xml server/

# Copy source directories
COPY common/src common/src
COPY server/src server/src

# Package only the server module and its dependencies (common)
RUN mvn clean package -pl server -am -DskipTests

# --- Stage 2: Runtime image ---
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Copy the built jar
COPY --from=builder /build/server/target/server-1.0-SNAPSHOT.jar server.jar

# Default environment variables
ENV NODE_ID="node-1"
ENV HOST="localhost"
ENV PORT="6100"
ENV SEEDS=""
ENV JVM_OPTS=""

# Use a shell entrypoint to allow environment variable expansion (like $SEEDS and $JVM_OPTS)
ENTRYPOINT exec java $JVM_OPTS -cp server.jar cluster.ClusterNode "$NODE_ID" "$HOST" "$PORT" $SEEDS
