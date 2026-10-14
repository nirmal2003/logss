#!/usr/bin/env bash
# Builds the demo and starts it with the OpenTelemetry Java agent, sending traces to logss.
# Start logss first (java -jar target/logss.jar from the repository root), then run this.
set -euo pipefail
cd "$(dirname "$0")"

AGENT_VERSION="2.32.0"
AGENT_JAR="${OTEL_AGENT_JAR:-.agent/opentelemetry-javaagent-${AGENT_VERSION}.jar}"
if [[ ! -f "$AGENT_JAR" ]]; then
  echo "Downloading the OpenTelemetry Java agent ${AGENT_VERSION}..."
  mkdir -p .agent
  curl -fsSL -o "$AGENT_JAR" \
    "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${AGENT_VERSION}/opentelemetry-javaagent.jar"
fi

# The demo depends on logss-spring (request and response bodies); install it locally first.
../mvnw -q -f ../logss-spring/pom.xml install -DskipTests
../mvnw -q -f pom.xml package -DskipTests

export OTEL_SERVICE_NAME=logss-demo
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
export OTEL_METRICS_EXPORTER=none
export OTEL_LOGS_EXPORTER=none
# Show the value bound to each ? in SQL.
export OTEL_INSTRUMENTATION_JDBC_EXPERIMENTAL_CAPTURE_QUERY_PARAMETERS=true
# A span per controller method, so issues point at the method that caused them.
export OTEL_INSTRUMENTATION_COMMON_EXPERIMENTAL_CONTROLLER_TELEMETRY_ENABLED=true
# Request and response bodies, via logss-spring.
export LOGSS_CAPTURE_ENABLED=true

exec java -javaagent:"$AGENT_JAR" -jar target/logss-demo.jar
