//! Backup job definitions and their JSON store (`filesDir/jobs.json`). The file format is unchanged.

use std::fs;
use std::path::PathBuf;
use std::sync::Mutex;

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::error::Result;
use crate::state;

fn d_interval() -> i32 {
    60
}
fn d_hour() -> i32 {
    2
}
fn d_days() -> i32 {
    0x7F
}
fn d_true() -> bool {
    true
}
fn d_transfers() -> i32 {
    4
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Job {
    pub id: String,
    #[serde(default)]
    pub name: String,
    /// Absolute path of the folder on the device.
    #[serde(default)]
    pub source: String,
    #[serde(default)]
    pub remote: String,
    #[serde(default)]
    pub remote_path: String,
    /// false = copy, true = sync (also deletes remote leftovers).
    #[serde(default)]
    pub sync: bool,
    /// 0 = every N minutes, 1 = at a time of day on chosen weekdays.
    #[serde(default)]
    pub trigger: i32,
    #[serde(default = "d_interval")]
    pub interval_min: i32,
    #[serde(default = "d_hour")]
    pub hour: i32,
    #[serde(default)]
    pub minute: i32,
    /// Bit 0 = Monday ... bit 6 = Sunday.
    #[serde(default = "d_days")]
    pub days: i32,
    #[serde(default = "d_true")]
    pub wifi_only: bool,
    #[serde(default)]
    pub charging_only: bool,
    /// One pattern per line.
    #[serde(default)]
    pub exclude: String,
    /// One pattern per line (optional).
    #[serde(default)]
    pub include: String,
    /// Parallel uploads, 1..=8.
    #[serde(default = "d_transfers")]
    pub transfers: i32,
    #[serde(default = "d_true")]
    pub enabled: bool,
    #[serde(default)]
    pub last_run: i64,
    #[serde(default)]
    pub last_status: String,
}

impl Job {
    pub fn dest(&self) -> String {
        format!("{}:{}", self.remote, self.remote_path)
    }
}

static STORE: Mutex<Option<Vec<Job>>> = Mutex::new(None);

fn file() -> Result<PathBuf> {
    Ok(state::paths()?.files.join("jobs.json"))
}

/// Reads the file; entries that cannot be parsed are skipped instead of dropping the whole list.
fn load() -> Result<Vec<Job>> {
    let path = file()?;
    let text = match fs::read_to_string(&path) {
        Ok(t) => t,
        Err(_) => return Ok(Vec::new()),
    };
    let items: Vec<Value> = serde_json::from_str(&text).unwrap_or_default();
    let mut jobs = Vec::new();
    for v in items {
        if let Ok(mut j) = serde_json::from_value::<Job>(v) {
            j.transfers = j.transfers.clamp(1, 8);
            jobs.push(j);
        }
    }
    Ok(jobs)
}

fn persist(jobs: &[Job]) -> Result<()> {
    let path = file()?;
    let tmp = PathBuf::from(format!("{}.tmp", path.display()));
    fs::write(&tmp, serde_json::to_vec(jobs)?)?;
    fs::rename(&tmp, &path)?;
    Ok(())
}

fn with_jobs<R>(f: impl FnOnce(&mut Vec<Job>) -> Result<R>) -> Result<R> {
    let mut g = STORE.lock().unwrap_or_else(|p| p.into_inner());
    if g.is_none() {
        *g = Some(load()?);
    }
    match g.as_mut() {
        Some(list) => f(list),
        None => f(&mut Vec::new()),
    }
}

pub fn all() -> Result<Vec<Job>> {
    with_jobs(|l| Ok(l.clone()))
}

pub fn get(id: &str) -> Result<Option<Job>> {
    with_jobs(|l| Ok(l.iter().find(|j| j.id == id).cloned()))
}

pub fn save(mut job: Job) -> Result<()> {
    job.transfers = job.transfers.clamp(1, 8);
    with_jobs(|l| {
        match l.iter().position(|j| j.id == job.id) {
            Some(i) => l[i] = job,
            None => l.push(job),
        }
        persist(l)
    })
}

pub fn delete(id: &str) -> Result<()> {
    with_jobs(|l| {
        l.retain(|j| j.id != id);
        persist(l)
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn old_files_load_with_defaults() {
        let j: Job = serde_json::from_str(r#"{"id":"x"}"#).unwrap();
        assert_eq!((j.interval_min, j.hour, j.days, j.transfers), (60, 2, 0x7F, 4));
        assert!(j.wifi_only && j.enabled && !j.sync);
    }

    #[test]
    fn round_trip_uses_camel_case() {
        let j: Job = serde_json::from_str(r#"{"id":"x","remotePath":"p","wifiOnly":false}"#).unwrap();
        assert_eq!(j.remote_path, "p");
        assert!(!j.wifi_only);
        let out = serde_json::to_string(&j).unwrap();
        assert!(out.contains("\"remotePath\":\"p\"") && out.contains("\"lastStatus\""));
    }
}
