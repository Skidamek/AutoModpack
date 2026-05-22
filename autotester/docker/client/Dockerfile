FROM ubuntu:26.04

ARG HEADLESSMC_VERSION=2.9.0

ENV DEBIAN_FRONTEND=noninteractive
ENV HMC_HOME=/work/hmc-cache

# Install Java 17, 21, 25 from repos
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        unzip \
        bash \
        procps \
        libatomic1 \
        libx11-6 \
        libxext6 \
        libxrender1 \
        libxtst6 \
        libxi6 \
        libxrandr2 \
        libxxf86vm1 \
        libgl1 \
        libglx-mesa0 \
        libasound2t64 \
        openjdk-17-jre-headless \
        openjdk-21-jre-headless \
        openjdk-25-jre-headless \
    && rm -rf /var/lib/apt/lists/*

# Detect architecture at build time and fetch the matching HMC binary
RUN HMC_ARCH="$(uname -m)" && \
    case "$HMC_ARCH" in \
        x86_64) HMC_BIN="headlessmc-launcher-linux-x64" ;; \
        aarch64) HMC_BIN="headlessmc-launcher-linux-arm64" ;; \
        *) echo "Unsupported architecture: $HMC_ARCH"; exit 1 ;; \
    esac && \
    mkdir -p /opt/headlessmc /opt/automodpack /work/game /work/hmc-cache && \
    curl -fsSL \
        "https://github.com/headlesshq/headlessmc/releases/download/${HEADLESSMC_VERSION}/${HMC_BIN}" \
        -o /usr/local/bin/hmc && \
    chmod +x /usr/local/bin/hmc

COPY run-headlessmc-client /opt/automodpack/run-headlessmc-client
RUN chmod +x /opt/automodpack/run-headlessmc-client

WORKDIR /work
ENTRYPOINT []
