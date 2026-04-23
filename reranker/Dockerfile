FROM python:3.11-slim

WORKDIR /app

# 시스템 라이브러리 (torch / FlagEmbedding 공통 요구)
RUN apt-get update && apt-get install -y --no-install-recommends \
    git \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 모델은 첫 요청 시 다운로드 (~2GB). 빌드 시 미리 받으려면 아래 주석 해제:
# RUN python -c "from FlagEmbedding import FlagReranker; FlagReranker('BAAI/bge-reranker-v2-m3')"

COPY main.py .

ENV RERANKER_MODEL=BAAI/bge-reranker-v2-m3
ENV RERANKER_FP16=true

EXPOSE 8000

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
