# HanTerm

Android tablet SSH client whose core proposition is decoupling the Android IME
pipeline from the terminal keyboard pipeline so Chinese pinyin IMEs work
naturally inside a remote shell.

## Language

### Connection

**ConnectionProfile**:
The durable single-host connection picture — host, port, username, and
references to stored credentials (encrypted password blob and/or imported
private key). Owned by the profile module; not a live SSH session.
_Avoid_: ConnectionStore, config, account

**ConnectionDraft**:
The in-form edit state for a ConnectionProfile. Password field holds only
newly typed plaintext during the editing session; after load it is empty.
_Avoid_: form state, ConnectionConfig

**ProfileSnapshot**:
The result of loading a ConnectionProfile for editing: a ConnectionDraft
(password always empty) plus whether a password blob is already stored.
_Avoid_: LoadResult, ConfigState

**prepareConnect**:
The profile intent that turns a ConnectionDraft into connect parameters
(host, port, username, Auth), persisting non-empty draft fields as a side
effect the same way Connect does today.
_Avoid_: resolveAuth, applyDraftForConnect (implementation details)

**ConnectPrepared**:
The output of prepareConnect: host, port, username, and a materialized Auth
ready for ConnectionRuntime.connect.
_Avoid_: ConnectParams, ResolvedConnect

**SaveOutcome**:
The output of an explicit profile save: the draft the UI should keep
(password cleared) and whether a password blob remains stored.
_Avoid_: SaveResult

**ConnectionRuntime**:
The module that owns the live SSH session resource graph (session, bridge,
adapter job) and teardown ordering. Process-scoped via HanTermApplication.
_Avoid_: session manager, connection service

**ConnectionView**:
The minimal capability surface the UI consumes from ConnectionRuntime for a
live (or idle) session: write, read, resize, lastCloseReason. Hides
SshSession / PtyBridge topology.
_Avoid_: endpoint triple, session handle, resource bundle
