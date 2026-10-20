# syntax=docker/dockerfile:1
FROM python:3.12.15-slim-bookworm@sha256:34386ef0cb081344d7ec1c103ba398e6e9f64e9ab3a1509accc92a4e24a07258
WORKDIR /opt/catalogue-localization
COPY runtime-requirements.txt ./
RUN pip install --no-cache-dir -r runtime-requirements.txt
COPY helper.py ./
USER 10001:10001
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 OPENBLAS_NUM_THREADS=1
ENTRYPOINT ["python", "helper.py", "--bind", "0.0.0.0", "--model", "/model"]
