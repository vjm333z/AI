#!/usr/bin/env bash
# templated 컬렉션 10건씩 배치 적재
# 신규 성공이 0건이면 전체 완료로 판단하고 종료
# Groq 무료 tier rate limit 대비: 배치 간 BATCH_WAIT초 대기

BASE_URL="http://localhost:8080"
LIMIT=10
BATCH_WAIT=15   # 배치 간 대기 (초) — rate limit 여유 확보
batch=1

while true; do
    echo ""
    echo "=== 배치 #${batch} 시작 ($(date '+%H:%M:%S')) ==="
    response=$(curl -s -X POST "${BASE_URL}/api/rag/index/templated?limit=${LIMIT}")

    echo "응답: ${response}"

    # 신규성공 N건 파싱
    new_ok=$(echo "$response" | grep -oE '신규성공 [0-9]+' | grep -oE '[0-9]+' || echo "0")

    if [ "$new_ok" = "0" ] || [ -z "$new_ok" ]; then
        echo ""
        echo "신규 적재 0건 → 전체 완료. 총 ${batch}회 배치 실행."
        break
    fi

    echo "신규 적재 ${new_ok}건 완료."

    # success=false 이면 오류로 중단
    if echo "$response" | grep -q '"success":false'; then
        echo "오류 감지, 중단."
        break
    fi

    echo "${BATCH_WAIT}초 대기 후 다음 배치..."
    sleep $BATCH_WAIT
    batch=$((batch + 1))
done
