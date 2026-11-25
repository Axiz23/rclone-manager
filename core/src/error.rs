//! Error type shared by the whole crate.
//!
//! Every variant maps to a Java exception type in `bridge.rs`, so the Kotlin code keeps catching the same
//! exceptions it did before the core moved to Rust.

use std::fmt;

#[derive(Debug, Clone)]
pub enum Error {
    /// The server answered with an error status. Maps to `ApiException(status, message)`.
    Api { status: i32, message: String },
    /// The operation was aborted through `cancel(tag)`. Maps to `IOException("Canceled")`.
    Canceled,
    /// The adapter profile is invalid. Maps to `AdapterException`.
    Adapter(String),
    /// Anything else (I/O, network, bad input). Maps to `IOException`.
    Io(String),
}

pub type Result<T> = std::result::Result<T, Error>;

impl Error {
    pub fn io(message: impl Into<String>) -> Self {
        Error::Io(message.into())
    }

    pub fn is_canceled(&self) -> bool {
        matches!(self, Error::Canceled)
    }

    pub fn message(&self) -> String {
        match self {
            Error::Api { message, .. } => message.clone(),
            Error::Canceled => "Canceled".to_string(),
            Error::Adapter(m) | Error::Io(m) => m.clone(),
        }
    }

    /// Name of the Java exception class this error becomes (used in log lines).
    pub fn kind(&self) -> &'static str {
        match self {
            Error::Api { .. } => "ApiException",
            Error::Adapter(_) => "AdapterException",
            _ => "IOException",
        }
    }
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.message())
    }
}

impl std::error::Error for Error {}

impl From<std::io::Error> for Error {
    fn from(e: std::io::Error) -> Self {
        Error::Io(e.to_string())
    }
}

impl From<serde_json::Error> for Error {
    fn from(e: serde_json::Error) -> Self {
        Error::Io(e.to_string())
    }
}
