FROM python:3-slim

# The certificate is generated at container start; openssl carries the CLI.
RUN apt-get update \
    && apt-get install -y --no-install-recommends openssl \
    && rm -rf /var/lib/apt/lists/*

COPY serve.py /opt/static-host/serve.py

ENTRYPOINT ["python3", "/opt/static-host/serve.py"]
