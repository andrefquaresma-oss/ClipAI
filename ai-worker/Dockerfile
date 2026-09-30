FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    WHISPER_DOWNLOAD_ROOT=/models \
    PADDLE_PDX_CACHE_HOME=/models/paddlex \
    MEDIA_STORAGE_ROOT=/var/lib/clipai/media
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 libgomp1 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir paddlepaddle==3.2.0 \
      -i https://www.paddlepaddle.org.cn/packages/stable/cpu/ \
    && pip install --no-cache-dir -r requirements.txt
COPY app.py worker_utils.py scoreboard_ocr.py ./
EXPOSE 8000
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
