//! Thread-safe task data & time logic. No UIKit or Swift dependencies.
//! UniFFI retains exported objects as Arc<ScheduleManager>.

use chrono::{LocalResult, NaiveDateTime, TimeZone, Utc};
use chrono_tz::Tz;
use rusqlite::{params, Connection, OpenFlags, Row};
use std::{sync::{Arc, Mutex, MutexGuard}, time::Duration};
use thiserror::Error;

uniffi::setup_scaffolding!();

const MAX_TITLE_CHARS: usize = 180;
const MAX_NOTES_CHARS: usize = 4_000;

#[derive(Clone, Debug, uniffi::Record)]
pub struct Task {
    pub id: String,
    pub title: String,
    pub notes: String,
    /// UTC epoch seconds. It stays the same instant if the phone travels.
    pub due_at_utc: i64,
    /// IANA zone in which the user created/specified the reminder.
    pub time_zone: String,
    pub is_completed: bool,
    pub created_at_utc: i64,
}

#[derive(Debug, Error, uniffi::Error)]
pub enum TaskError {
    #[error("Dữ liệu không hợp lệ: {message}")]
    Validation { message: String },
    #[error("Múi giờ không hợp lệ: {message}")]
    InvalidTimeZone { message: String },
    #[error("Giờ không tồn tại do chuyển giờ mùa hè: {message}")]
    NonexistentLocalTime { message: String },
    #[error("Giờ bị trùng do chuyển giờ mùa hè: {message}")]
    AmbiguousLocalTime { message: String },
    #[error("Không tìm thấy công việc")]
    NotFound,
    #[error("Lỗi cơ sở dữ liệu: {message}")]
    Database { message: String },
}

impl From<rusqlite::Error> for TaskError {
    fn from(value: rusqlite::Error) -> Self {
        Self::Database { message: value.to_string() }
    }
}

#[derive(uniffi::Object)]
pub struct ScheduleManager {
    // Connection is Send but not Sync; Mutex serializes every SQLite call.
    // The UniFFI-exported object is automatically Arc-backed, so no extra Arc needed here.
    db: Mutex<Connection>,
}

fn from_row(row: &Row<'_>) -> rusqlite::Result<Task> {
    Ok(Task {
        id: row.get(0)?,
        title: row.get(1)?,
        notes: row.get(2)?,
        due_at_utc: row.get(3)?,
        time_zone: row.get(4)?,
        is_completed: row.get(5)?,
        created_at_utc: row.get(6)?,
    })
}

const TASK_COLUMNS: &str =
    "id, title, notes, due_at_utc, time_zone, is_completed, created_at_utc";

fn validated_details(title: &str, notes: &str) -> Result<String, TaskError> {
    let title = title.trim();
    if title.is_empty() || title.chars().count() > MAX_TITLE_CHARS {
        return Err(TaskError::Validation {
            message: format!("Tiêu đề phải từ 1 đến {MAX_TITLE_CHARS} ký tự"),
        });
    }
    if notes.chars().count() > MAX_NOTES_CHARS {
        return Err(TaskError::Validation {
            message: format!("Ghi chú không được quá {MAX_NOTES_CHARS} ký tự"),
        });
    }
    Ok(title.to_owned())
}

fn resolve_local_time(local: &str, zone: &str) -> Result<i64, TaskError> {
    let tz: Tz = zone.parse().map_err(|_| TaskError::InvalidTimeZone {
        message: zone.to_owned(),
    })?;
    let naive = NaiveDateTime::parse_from_str(local, "%Y-%m-%dT%H:%M:%S")
        .map_err(|_| TaskError::Validation {
            message: "Ngày giờ phải có dạng YYYY-MM-DDTHH:MM:SS".to_owned(),
        })?;

    let local_date = match tz.from_local_datetime(&naive) {
        LocalResult::Single(value) => value,
        LocalResult::None => return Err(TaskError::NonexistentLocalTime {
            message: format!("{local} ({zone})"),
        }),
        LocalResult::Ambiguous(_, _) => return Err(TaskError::AmbiguousLocalTime {
            message: format!("{local} ({zone})"),
        }),
    };
    let epoch_seconds = local_date.with_timezone(&Utc).timestamp();
    if epoch_seconds <= Utc::now().timestamp() {
        return Err(TaskError::Validation {
            message: "Thời gian nhắc phải ở trong tương lai".into(),
        });
    }
    Ok(epoch_seconds)
}

impl ScheduleManager {
    fn lock(&self) -> Result<MutexGuard<'_, Connection>, TaskError> {
        self.db.lock().map_err(|_| TaskError::Database {
            message: "Khóa SQLite không còn khả dụng".into(),
        })
    }
}

#[uniffi::export]
impl ScheduleManager {
    #[uniffi::constructor]
    pub fn new(database_path: String) -> Result<Arc<Self>, TaskError> {
        // The in-memory path is useful for Rust unit tests.
        if database_path.trim().is_empty() {
            return Err(TaskError::Validation {
                message: "Thiếu đường dẫn cơ sở dữ liệu".into(),
            });
        }
        let conn = Connection::open_with_flags(
            database_path,
            OpenFlags::SQLITE_OPEN_READ_WRITE | OpenFlags::SQLITE_OPEN_CREATE |
                OpenFlags::SQLITE_OPEN_FULL_MUTEX,
        )?;
        conn.busy_timeout(Duration::from_secs(3))?;
        conn.pragma_update(None, "journal_mode", "WAL")?;
        conn.execute_batch(
            "PRAGMA synchronous=NORMAL;
             PRAGMA foreign_keys=ON;
             CREATE TABLE IF NOT EXISTS tasks (
                id TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL,
                notes TEXT NOT NULL DEFAULT '',
                due_at_utc INTEGER NOT NULL,
                time_zone TEXT NOT NULL,
                is_completed INTEGER NOT NULL DEFAULT 0 CHECK (is_completed IN (0, 1)),
                created_at_utc INTEGER NOT NULL
             );
             CREATE INDEX IF NOT EXISTS idx_tasks_due ON tasks(is_completed, due_at_utc);
             PRAGMA user_version = 1;"
        )?;
        Ok(Arc::new(Self { db: Mutex::new(conn) }))
    }

    /// Creates a task using civil wall time + IANA zone, not the device's UTC offset.
    pub fn create_task(
        &self,
        title: String,
        notes: String,
        due_local: String,
        time_zone: String,
    ) -> Result<Task, TaskError> {
        let title = validated_details(&title, &notes)?;
        let due_at_utc = resolve_local_time(&due_local, &time_zone)?;
        let now = Utc::now().timestamp();
        let mut db = self.lock()?;
        let tx = db.transaction()?;
        // SQLite generates IDs, avoiding an extra UUID/random dependency.
        tx.execute(
            "INSERT INTO tasks (id, title, notes, due_at_utc, time_zone, created_at_utc)
             VALUES (lower(hex(randomblob(16))), ?1, ?2, ?3, ?4, ?5)",
            params![title, notes, due_at_utc, time_zone, now],
        )?;
        let row_id = tx.last_insert_rowid();
        let task = tx.query_row(
            &format!("SELECT {TASK_COLUMNS} FROM tasks WHERE rowid = ?1"),
            [row_id],
            from_row,
        )?;
        tx.commit()?;
        Ok(task)
    }

    pub fn list_tasks(&self) -> Result<Vec<Task>, TaskError> {
        let db = self.lock()?;
        let sql = format!(
            "SELECT {TASK_COLUMNS} FROM tasks ORDER BY is_completed, due_at_utc, created_at_utc"
        );
        let mut statement = db.prepare(&sql)?;
        let rows = statement.query_map([], from_row)?;
        rows.collect::<rusqlite::Result<Vec<_>>>().map_err(Into::into)
    }

    pub fn update_task(
        &self,
        task_id: String,
        title: String,
        notes: String,
        due_local: String,
        time_zone: String,
    ) -> Result<(), TaskError> {
        let title = validated_details(&title, &notes)?;
        let due_at_utc = resolve_local_time(&due_local, &time_zone)?;
        let db = self.lock()?;
        let changed = db.execute(
            "UPDATE tasks SET title=?1, notes=?2, due_at_utc=?3, time_zone=?4 WHERE id=?5",
            params![title, notes, due_at_utc, time_zone, task_id],
        )?;
        if changed == 0 { return Err(TaskError::NotFound); }
        Ok(())
    }

    pub fn set_completed(&self, task_id: String, completed: bool) -> Result<(), TaskError> {
        let db = self.lock()?;
        let changed = db.execute(
            "UPDATE tasks SET is_completed=?1 WHERE id=?2",
            params![completed, task_id],
        )?;
        if changed == 0 { return Err(TaskError::NotFound); }
        Ok(())
    }

    pub fn delete_task(&self, task_id: String) -> Result<(), TaskError> {
        let db = self.lock()?;
        let changed = db.execute("DELETE FROM tasks WHERE id=?1", [task_id])?;
        if changed == 0 { return Err(TaskError::NotFound); }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn crud_round_trip() {
        let manager = ScheduleManager::new(":memory:".into()).unwrap();
        let task = manager.create_task(
            "  Việc cần làm  ".into(), "Ghi chú".into(),
            "2099-05-05T09:30:00".into(), "Asia/Ho_Chi_Minh".into(),
        ).unwrap();
        assert_eq!(task.title, "Việc cần làm");
        assert_eq!(manager.list_tasks().unwrap().len(), 1);
        manager.set_completed(task.id.clone(), true).unwrap();
        assert!(manager.list_tasks().unwrap()[0].is_completed);
        manager.update_task(task.id.clone(), "Đã sửa".into(), "".into(),
                            "2099-05-06T09:30:00".into(), "Asia/Ho_Chi_Minh".into()).unwrap();
        assert_eq!(manager.list_tasks().unwrap()[0].title, "Đã sửa");
        manager.delete_task(task.id).unwrap();
        assert!(manager.list_tasks().unwrap().is_empty());
    }

    #[test]
    fn invalid_and_ambiguous_dst() {
        // The transition happens in March/November 2026 in New York.
        let fixed = resolve_local_time("2026-03-08T02:30:00", "America/New_York");
        assert!(matches!(fixed, Err(TaskError::NonexistentLocalTime { .. })));
        let ambiguous = resolve_local_time("2026-11-01T01:30:00", "America/New_York");
        assert!(matches!(ambiguous, Err(TaskError::AmbiguousLocalTime { .. })));
    }

    #[test]
    fn reject_invalid_title() {
        let manager = ScheduleManager::new(":memory:".into()).unwrap();
        let result = manager.create_task("   ".into(), "".into(),
            "2099-01-01T08:00:00".into(), "Asia/Ho_Chi_Minh".into());
        assert!(matches!(result, Err(TaskError::Validation { .. })));
    }
}
