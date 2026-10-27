#!/bin/sh
set -eu

# Both processes share one Render Free instance; AI is reachable only on loopback.
python -m uvicorn app.main:app --app-dir /app/ai --host 127.0.0.1 --port 8001 --workers 1 &
ai_pid=$!
java -Xmx192m -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=32m \
    -XX:+ExitOnOutOfMemoryError -XX:MaxDirectMemorySize=16m -Xss512k -XX:+UseSerialGC -XX:ActiveProcessorCount=1 \
    -jar /app/app.jar --spring.profiles.include=render-free &
java_pid=$!

cleanup() {
    trap - EXIT INT TERM
    kill "$ai_pid" "$java_pid" 2>/dev/null || true
    wait "$ai_pid" 2>/dev/null || true
    wait "$java_pid" 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 143' TERM
trap 'exit 130' INT

# Fail the instance if either service dies, so Render can restart the complete pair.
while kill -0 "$ai_pid" 2>/dev/null && kill -0 "$java_pid" 2>/dev/null; do
    sleep 1
done
exit 1
