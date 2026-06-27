# Git repository and ref the HeadlessMC launcher is built from. These are
# normally supplied by `autotester build-images` from settings.yaml
# (headlessmc.repo/ref). The defaults below are only a fallback for a bare
# `docker build` and point at upstream HeadlessMC; set headlessmc.repo/ref in
# settings.yaml to build a patched launcher (e.g. for MC 26.2 headless).
ARG HEADLESSMC_REPO=https://github.com/headlesshq/headlessmc.git
ARG HEADLESSMC_REF=main

# ── Builder: compile the HeadlessMC launcher wrapper jar ──────────────
# HeadlessMC must be built with JDK 21 (its Gradle build rejects newer JDKs).
FROM eclipse-temurin:21-jdk AS hmc
ARG HEADLESSMC_REPO
ARG HEADLESSMC_REF
RUN apt-get update \
    && apt-get install -y --no-install-recommends git ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /build
# Fetch by ref so HEADLESSMC_REF can be a branch, tag, or commit SHA (a shallow
# `clone --branch` only accepts branch/tag names — fetching FETCH_HEAD also takes SHAs).
RUN git init -q . \
    && git remote add origin "${HEADLESSMC_REPO}" \
    && git fetch --depth 1 origin "${HEADLESSMC_REF}" \
    && git checkout -q FETCH_HEAD \
    && ./gradlew --no-daemon headlessmc-launcher-wrapper:build -x test \
    && jar="$(find headlessmc-launcher-wrapper/build/libs -maxdepth 1 \
                -name 'headlessmc-launcher-wrapper-*.jar' \
                ! -name '*-dev.jar' ! -name '*-javadoc.jar' ! -name '*-sources.jar' \
                | head -n1)" \
    && test -n "$jar" \
    && cp "$jar" /headlessmc-launcher-wrapper.jar

# ── Runtime client image ──────────────────────────────────────────────
FROM ubuntu:26.04

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

RUN mkdir -p /opt/headlessmc /opt/automodpack /work/game /work/hmc-cache \
        /work/HeadlessMC /work/hmc-shared-versions \
    && chmod a+rwx /work/HeadlessMC /work/hmc-shared-versions

# Install the HeadlessMC launcher built above as the `hmc` command.
COPY --from=hmc /headlessmc-launcher-wrapper.jar \
     /opt/headlessmc/headlessmc-launcher-wrapper.jar
RUN printf '#!/usr/bin/env bash\nexec java -jar /opt/headlessmc/headlessmc-launcher-wrapper.jar "$@"\n' \
        > /usr/local/bin/hmc \
    && chmod +x /usr/local/bin/hmc

COPY run-headlessmc-client /opt/automodpack/run-headlessmc-client
RUN chmod +x /opt/automodpack/run-headlessmc-client

WORKDIR /work
ENTRYPOINT []
