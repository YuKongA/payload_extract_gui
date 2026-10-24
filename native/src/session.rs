use std::collections::HashMap;
use std::sync::{Arc, LazyLock, Mutex};

use anyhow::{Context, anyhow};
use payload_extract::payload::PayloadView;

/// Keeps parsed metadata and its mapping alive for handles and in-flight work.
pub(crate) struct Session {
    pub(crate) input: String,
    pub(crate) payload: PayloadView,
}

#[derive(Default)]
struct Registry {
    last_handle: i64,
    sessions: HashMap<i64, Arc<Session>>,
}

impl Registry {
    fn insert(&mut self, session: Arc<Session>) -> anyhow::Result<i64> {
        // Never recycle an ID: a stale caller must not acquire a newer session.
        let handle = self
            .last_handle
            .checked_add(1)
            .context("Payload handle space exhausted")?;
        self.sessions.insert(handle, session);
        self.last_handle = handle;
        Ok(handle)
    }

    fn get(&self, handle: i64) -> anyhow::Result<Arc<Session>> {
        self.sessions
            .get(&handle)
            .cloned()
            .ok_or_else(|| anyhow!("Invalid payload handle: {handle}"))
    }
}

static SESSIONS: LazyLock<Mutex<Registry>> = LazyLock::new(|| Mutex::new(Registry::default()));

pub(crate) fn open(input: String) -> anyhow::Result<i64> {
    let payload = crate::document_input::open(&input)
        .unwrap_or_else(|| payload_extract::input::open(&input, false, None))
        .context("Failed to open payload")?;
    let session = Arc::new(Session { input, payload });
    SESSIONS
        .lock()
        .map_err(|_| anyhow!("Payload session registry is unavailable"))?
        .insert(session)
}

pub(crate) fn get(handle: i64) -> anyhow::Result<Arc<Session>> {
    SESSIONS
        .lock()
        .map_err(|_| anyhow!("Payload session registry is unavailable"))?
        .get(handle)
}

pub(crate) fn retain(handle: i64) -> anyhow::Result<i64> {
    let mut registry = SESSIONS
        .lock()
        .map_err(|_| anyhow!("Payload session registry is unavailable"))?;
    let session = registry.get(handle)?;
    registry.insert(session)
}

pub(crate) fn close(handle: i64) -> anyhow::Result<()> {
    let session = SESSIONS
        .lock()
        .map_err(|_| anyhow!("Payload session registry is unavailable"))?
        .sessions
        .remove(&handle)
        .ok_or_else(|| anyhow!("Invalid payload handle: {handle}"))?;
    // Unmap/free outside the registry lock, only after all other references end.
    drop(session);
    Ok(())
}
