# Breaking changes

## Generation and configuration redesign after v4.0.5

This generation redesign does not read or migrate persisted state created by the
`v4.0.5` release. Compatibility will be added in a separate change set based on
the `v4.0.5` tag.

Before upgrading, stop AutoModpack and make a complete backup of the
`automodpack` directories on the server and client. Do not remove the old data
until the new generation has been created and verified.

### Configuration names and fields

The canonical configuration files are now:

- `automodpack/server-config.json`
- `automodpack/client-config.json`

The following older names and fields are not imported automatically:

- `automodpack-server.json` → `server-config.json`
- `automodpack-client.json` → `client-config.json`
- `modpackConnections` in `client-config.json` → the current per-pack connection record under `automodpack/client/data/packs/<modpack-id>/connection.json`
- `addressToSend` / `portToSend` → `advertisedEndpointHost` / `advertisedEndpointPort`
- `tag` → `category`
- `selectedGroups` → `requestedGroups`
- older flat server group rules → the current `groups` structure

Copy settings into the canonical files manually. Preserve the old files as a
backup until the server and client start with the expected settings.

### Durable generation state

Generation records, checkpoints, transactions, selection digests, and related
client storage use the new current-format contracts. State from `v4.0.5` is not
silently converted. Create a fresh server generation and let the client create
fresh current-format state after the upgrade.

Legacy selected manifests and host state without a current generation pointer
are not imported. Old generated-copy state and deferred transactions are also
not resumed under the current contracts.

If an update transaction from the older release is present, do not delete it
while an update is running. Stop the affected process, keep the backup, and
recreate the current-format generation instead of expecting the old transaction
to resume.

### Recovery and quarantine data

The current preservation vault does not import the older
`automodpack/client/recovery` or `automodpack/client/quarantine` layouts. Keep
those directories in the backup. Manual recovery may be required until the
compatibility change based on `v4.0.5` is available.

### Upgrade outline

1. Stop the server and clients.
2. Back up the server and client `automodpack` directories.
3. Copy required settings into the canonical configuration files.
4. Create a fresh server generation and verify that it is hosted.
5. Connect a client and verify the new active projection, selection, and update
   behavior.
6. Keep the backup until the upgrade is complete.
