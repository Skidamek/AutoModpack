## Purpose

AutoModpack can reuse the already-established Minecraft connection to carry Iroh custom transport packets inside the
normal Netty pipeline. The tunnel is handled before Minecraft packet decoding, so it is no longer tied to login
custom-query packets and can survive across login, configuration, and play stages as long as the Minecraft connection
remains established.

This transport is currently used by the login/update flow when the server advertises `shareMinecraftConnection = true`.
Preload and offline flows use the same Iroh protocol over saved direct-IP and raw-TCP routes instead.

## Transport Scope

- Tunnel frames ride inside the existing Minecraft TCP connection.
- Frames are handled independently from Minecraft packet decoding and `packet_handler`.
- `HANDSHAKE` and `DATA` still use AutoModpack login custom query packets.
- The tunnel is full-duplex and push-based.
- The separate-socket raw TCP bootstrap still starts with the legacy `AMMH` discriminator, but now upgrades into the
  Iroh custom transport using `AMOK` followed by `AMID`.

## Netty Placement

AutoModpack installs dedicated handlers on every `net.minecraft.network.Connection`:

- inbound: before `decompress` when present, otherwise before `decoder` / `inbound_config`
- outbound: after `prepender`

That placement means:

- inbound frames are already decrypted if encryption is enabled
- inbound tunnel frames bypass Minecraft decompression
- outbound tunnel frames still use Minecraft framing and encryption
- outbound tunnel frames bypass Minecraft compression and encoder object translation
- AutoModpack frames are consumed before Minecraft packet decoding

## Outer Frame Format

Each AutoModpack transport frame uses this wire format:

1. `u32 magic = 0x414D5454` (`"AMTT"`)
2. `u8 version`
3. `u8 kind`
4. remaining bytes = kind-specific payload

### Version

- current version: `1`

### Kind Values

- `1 = IROH_TUNNEL`

Unknown or invalid kinds are fatal protocol errors and close the Minecraft connection.

## Iroh Tunnel Payload

The outer frame payload for `IROH_TUNNEL` reuses the existing `IrohTunnelEnvelope` unchanged.

Envelope layout:

1. `byte version`
2. `long sessionId`
3. `byte flags`
4. `byte[32] endpointId` only when `OPEN` is set
5. `varint packetCount`
6. repeated `varint packetLength`
7. repeated `byte[] packet`
8. `varint errorLength` followed by UTF-8 bytes only when `ERROR` is set

### Envelope Version

- current version: `1`

### Envelope Flags

- `OPEN = 0x01`
- `CLOSE = 0x02`
- `ERROR = 0x04`
- `READY = 0x08`

### Payload Size Cap

- maximum encoded envelope size: `256 KiB`
- queued packets are batched until the cap is reached
- oversized or malformed incoming envelopes are fatal protocol errors

## Session Lifecycle

1. The server sends the normal `DATA` login query and advertises:
    - `shareMinecraftConnection = true`
    - `endpointId`
    - `tunnelSessionId`
2. The server immediately creates a `ServerConnectionIrohTunnelSession` bound to the current Minecraft `Connection`.
3. The client parses `DATA`, creates a `ClientConnectionIrohTunnelSession`, registers it on the current Minecraft
   `Connection`, and immediately sends an `AMTT` frame with `kind = IROH_TUNNEL` carrying an `OPEN` envelope with the
   client Iroh endpoint id.
4. The server receives that frame before Minecraft decoding, bootstraps the peer, and replies with a `READY` envelope on
   the same connection-level transport.
5. The client starts the QUIC dial only after it receives `READY`.
6. Both sides push Iroh packets asynchronously through `IrohPeer.setOnTransmit(...)` and feed received packets back with
   `IrohPeer.injectPacket(...)`.
7. When the update flow finishes or fails, the session is closed and removed, but the connection-level handlers remain
   installed for the life of the Minecraft connection.

## Session Ownership

- tunnel sessions are scoped to the Minecraft `Connection`
- the server owns `tunnelSessionId` generation and sends it in `DATA`
- client and server registries clean up on:
    - normal close
    - error
    - connection disconnect
    - login completion
    - fallback activation

## Transport Behavior

- There is no poll loop.
- There is no “one outstanding `TUNNEL` query” restriction.
- There are no empty login-query keepalive responses.
- Whenever Iroh emits packets, AutoModpack wraps them in an `IrohTunnelEnvelope`, then an `AMTT` frame, and flushes them
  through the connection manager's coalesced event-loop drain.
- Incoming `AMTT` frames are decoded and dispatched before Minecraft packet decoding.

## Error Handling And Fallback

- Non-`AMTT` traffic is passed through untouched.
- `AMTT` frames with invalid version, invalid kind, malformed payload, or unexpected session state are fatal and close
  the Minecraft connection.
- If the connection-level Iroh tunnel fails during content fetch or download:
    - fall back to the current separate-socket Iroh transport
- There is no fallback to the removed login-query `TUNNEL` path.

## Login Timeout Handling

- Vanilla login timeout is suppressed while an active connection-level tunnel session is running during login.
- The server-side login tick counter is reset while the tunnel session is active.
- Normal timeout behavior resumes immediately after the session closes.

## Supported Stages

- login: supported and used now
- configuration: supported by transport design
- play: supported by transport design

Only the modpack update flow currently opens connection-level Iroh tunnel sessions, but the transport itself is no
longer tied to login custom-query packets.
