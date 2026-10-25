# GitHub repo and release tag the HeadlessMC launcher is downloaded from.
# These are normally supplied by `autotester build-images` from settings.yaml
# (headlessmc.repo/ref). HeadlessMC 3 publishes OS binaries on the release.
ARG HEADLESSMC_REPO=https://github.com/headlesshq/headlessmc
ARG HEADLESSMC_REF=3.0.0-RC8

FROM ubuntu:26.04

ARG HEADLESSMC_REPO
ARG HEADLESSMC_REF

ENV DEBIAN_FRONTEND=noninteractive
# A POSIX locale makes the JDK encode file paths as ASCII, so any non-ASCII
# mod file name cannot exist in the game directory; UTF-8 is the mod's contract.
ENV LANG=C.UTF-8 LC_ALL=C.UTF-8
ENV HMC_HOME=/work/hmc-cache
ENV HOME=/work
ENV XDG_CONFIG_HOME=/work/.config

# Most runs use HeadlessMC's LWJGL transformer. UI screenshot scenarios opt into
# Xvfb and Mesa so Minecraft renders the real interface in a virtual display;
# mesa-vulkan-drivers (lavapipe) completes that software stack for Minecraft
# 26.3+, whose renderpearl backend rejects Xvfb's GLX visuals and renders
# through Vulkan instead. iproute2 provides `tc` for the --netem qdisc.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        unzip \
        bash \
        procps \
        tini \
        libatomic1 \
        libasound2t64 \
        libgl1-mesa-dri \
        libglx-mesa0 \
        mesa-vulkan-drivers \
        libxcursor1 \
        libxi6 \
        libxinerama1 \
        libxrandr2 \
        libxxf86vm1 \
        openjdk-17-jre-headless \
        openjdk-21-jre-headless \
        openjdk-25-jre-headless \
        iproute2 \
        ethtool \
        xauth \
        xvfb \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /opt/headlessmc /opt/automodpack /work/game /work/hmc-cache \
        /work/HeadlessMC /work/hmc-shared-versions /work/.config/headlessmc \
    && chmod a+rwx /work /work/HeadlessMC /work/hmc-shared-versions /work/.config /work/.config/headlessmc

# HeadlessMC 3 ships a native launcher; the game still uses the JREs above.
# uname -m keeps this working with the Python docker builder (no buildx TARGETARCH).
RUN set -eu \
    && repo="${HEADLESSMC_REPO%.git}" \
    && arch="$(uname -m)" \
    && case "$arch" in \
         x86_64) hmc_arch=x64 ;; \
         aarch64|arm64) hmc_arch=arm64 ;; \
         *) echo "unsupported architecture: $arch" >&2; exit 1 ;; \
       esac \
    && curl -fsSL -o /usr/local/bin/hmc "${repo}/releases/download/${HEADLESSMC_REF}/headlessmc-launcher-linux-${hmc_arch}" \
    && chmod +x /usr/local/bin/hmc

COPY run-headlessmc-client /opt/automodpack/run-headlessmc-client
RUN chmod +x /opt/automodpack/run-headlessmc-client

WORKDIR /work
ENTRYPOINT []
