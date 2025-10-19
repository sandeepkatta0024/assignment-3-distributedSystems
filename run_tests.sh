#!/usr/bin/env bash
set -e

CONFIG="network.config"
COMPILE_DIR="target/classes"

cleanup() {
  echo "🧹 Cleaning up..."
  pkill -9 -f "council.CouncilMember" 2>/dev/null || true
  sleep 2
}

trap cleanup EXIT

cleanup
rm -rf logs
mkdir -p logs

echo "=== COMPILING ==="
rm -rf $COMPILE_DIR
mkdir -p $COMPILE_DIR
javac -d $COMPILE_DIR src/main/java/council/*.java

launch_member() {
  local id=$1
  local profile=$2
  java -cp $COMPILE_DIR council.CouncilMember $id --profile $profile --config $CONFIG < /dev/null > logs/$id.log 2>&1 &
  sleep 0.2
}

send_proposal() {
  local candidate=$1
  local port=$2
  # Send PROPOSE message in correct format
  echo "type=PROPOSE;from=script;v=$candidate" | nc -w 1 localhost $port 2>/dev/null || true
}

run_scenario() {
  echo ""
  echo "=========================================="
  echo "$1"
  echo "=========================================="
}

# ============================================
# Scenario 1: Ideal Network
# ============================================
run_scenario "Scenario 1 - Ideal Network"
for i in {1..9}; do
  launch_member "M${i}" reliable
done
sleep 3

echo "M4 proposes M5"
send_proposal "M5" 9004
sleep 5

echo "✅ Checking consensus..."
if grep -h "CONSENSUS" logs/*.log 2>/dev/null; then
  echo "✅ Scenario 1 PASSED"
else
  echo "❌ Scenario 1 FAILED - No consensus"
  echo "Sample logs:"
  head -20 logs/M4.log
fi

cleanup

# ============================================
# Scenario 2: Concurrent Proposals
# ============================================
run_scenario "Scenario 2 - Concurrent Proposals"
for i in {1..9}; do
  launch_member "M${i}" reliable
done
sleep 3

echo "M1 proposes M1, M8 proposes M8 (concurrent)"
send_proposal "M1" 9001 &
sleep 0.5
send_proposal "M8" 9008 &
sleep 8

echo "✅ Checking consensus..."
if grep -h "CONSENSUS" logs/*.log 2>/dev/null | sort -u; then
  WINNER=$(grep -h "CONSENSUS" logs/*.log | head -1 | grep -oE 'M[1-9]')
  echo "✅ Scenario 2 PASSED - Winner: $WINNER"
else
  echo "❌ Scenario 2 FAILED - No consensus"
fi

cleanup

# ============================================
# Scenario 3a: Standard member proposes
# ============================================
run_scenario "Scenario 3a - Standard member proposes"
launch_member M1 reliable
launch_member M2 latent
launch_member M3 failure
for i in {4..9}; do
  launch_member "M${i}" standard
done
sleep 3

echo "M4 proposes M1"
send_proposal "M1" 9004
sleep 8

echo "✅ Checking consensus..."
if grep -h "CONSENSUS" logs/*.log 2>/dev/null; then
  echo "✅ Scenario 3a PASSED"
else
  echo "❌ Scenario 3a FAILED - No consensus"
fi

cleanup

# ============================================
# Scenario 3b: Latent member proposes
# ============================================
run_scenario "Scenario 3b - Latent member proposes"
launch_member M1 reliable
launch_member M2 latent
launch_member M3 failure
for i in {4..9}; do
  launch_member "M${i}" standard
done
sleep 3

echo "M2 (latent) proposes M2"
send_proposal "M2" 9002
sleep 12

echo "✅ Checking consensus..."
if grep -h "CONSENSUS" logs/*.log 2>/dev/null; then
  echo "✅ Scenario 3b PASSED"
else
  echo "❌ Scenario 3b FAILED - No consensus"
fi

cleanup

# ============================================
# Scenario 3c: Failing member crashes
# ============================================
run_scenario "Scenario 3c - Failing member crashes during proposal"
launch_member M1 reliable
launch_member M2 latent
launch_member M3 failure
for i in {4..9}; do
  launch_member "M${i}" standard
done
sleep 3

echo "M3 (failure) proposes M3"
send_proposal "M3" 9003
sleep 4

echo "M1 proposes M1 to recover"
send_proposal "M1" 9001
sleep 8

echo "✅ Checking consensus..."
if grep -h "CONSENSUS" logs/*.log 2>/dev/null; then
  echo "✅ Scenario 3c PASSED"
else
  echo "❌ Scenario 3c FAILED - No consensus"
fi

echo ""
echo "=========================================="
echo "🎉 All scenarios complete!"
echo "=========================================="
echo "Log files:"
ls -lh logs/