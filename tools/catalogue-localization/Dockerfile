# syntax=docker/dockerfile:1
FROM python:3.12.12-slim-bookworm@sha256:593bd06efe90efa80dc4eee3948be7c0fde4134606dd40d8dd8dbcade98e669c
WORKDIR /opt/catalogue-localization
COPY runtime-requirements.txt ./
RUN pip install --no-cache-dir -r runtime-requirements.txt
COPY helper.py ./
USER 10001:10001
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 OPENBLAS_NUM_THREADS=1
ENTRYPOINT ["python", "helper.py", "--bind", "0.0.0.0", "--model", "/model"]
