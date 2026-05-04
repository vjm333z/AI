FROM python:3.11-slim

WORKDIR /app

# 시스템 패키지
#  - git: huggingface 모델 다운로드 시 필요
#  - ffmpeg: faster-whisper 로컬 STT 디코딩 (Groq API 모드만 쓰면 불필요하지만 폴백 대비 포함)
RUN apt-get update && apt-get install -y --no-install-recommends \
    git ffmpeg \
    && rm -rf /var/lib/apt/lists/*

# torch CPU 빌드 우선 설치 — GPU 빌드(2.5GB)보다 이미지 절반 크기
RUN pip install --no-cache-dir \
    torch==2.4.1 \
    --index-url https://download.pytorch.org/whl/cpu

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 모델은 첫 요청 시 다운로드 (~2GB). 빌드 시 미리 받으려면 아래 주석 해제:
# RUN python -c "from sentence_transformers import CrossEncoder; CrossEncoder('BAAI/bge-reranker-v2-m3')"

COPY . .

ENV RERANKER_MODEL=BAAI/bge-reranker-v2-m3 \
    RERANKER_FP16=true \
    PYTHONUNBUFFERED=1 \
    PYTHONIOENCODING=utf-8

EXPOSE 8000

# uvicorn workers는 1로 — reranker 모델은 워커당 별도 메모리(~2GB)
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
