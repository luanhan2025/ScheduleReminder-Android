//! Schedule Reminder v1.1: Rust owns SQLite, schedule validation and occurrence generation.
//! Android owns alarm delivery and audio playback via notification channels.
use chrono::{Duration as ChronoDuration, LocalResult, NaiveDateTime, NaiveTime, TimeZone, Utc};
use chrono_tz::Tz;
use rusqlite::{params, Connection, OpenFlags, Row, Transaction};
use std::{collections::HashSet, sync::{Arc, Mutex, MutexGuard}, time::Duration};
use thiserror::Error;

uniffi::setup_scaffolding!();

const MAX_TITLE_CHARS: usize = 180;
const MAX_NOTES_CHARS: usize = 4_000;
const MAX_OCCURRENCES: usize = 512;

#[derive(Clone, Debug, uniffi::Record)]
pub struct Task {
    pub id: String,
    pub title: String,
    pub notes: String,
    pub due_at_utc: i64,
    pub time_zone: String,
    pub is_completed: bool,
    pub created_at_utc: i64,
    pub sound_id: String,
    pub sound_name: String,
    pub repeat_mode: String,
    pub duration_days: u32,
    pub interval_minutes: u32,
    pub daily_times_csv: String,
    pub reminder_count: u32,
}

/// The selected sound is imported into private Android storage; only its safe filename is saved.
#[derive(Clone, Debug, uniffi::Record)]
pub struct TaskOptions {
    pub sound_id: String,
    pub sound_name: String,
    /// `once`, `slots` (selected times each day), or `interval` (elapsed minutes).
    pub repeat_mode: String,
    /// A 1–7 day schedule window, counting the start date as day 1 for slots.
    pub duration_days: u32,
    /// Only used by interval mode.
    pub interval_minutes: u32,
    /// Only used by slots mode; e.g. `08:00,12:00,20:30`.
    pub daily_times_csv: String,
}

impl TaskOptions {
    fn once() -> Self {
        Self { sound_id: String::new(), sound_name: String::new(), repeat_mode: "once".into(),
            duration_days: 1, interval_minutes: 0, daily_times_csv: String::new() }
    }
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct ReminderOccurrence {
    pub id: String,
    pub task_id: String,
    pub fire_at_utc: i64,
}

#[derive(Debug, Error, uniffi::Error)]
pub enum TaskError {
    #[error("Dữ liệu không hợp lệ: {detail}")]
    Validation { detail: String },
    #[error("Múi giờ không hợp lệ: {detail}")]
    InvalidTimeZone { detail: String },
    #[error("Giờ không tồn tại do chuyển giờ mùa hè: {detail}")]
    NonexistentLocalTime { detail: String },
    #[error("Giờ bị trùng do chuyển giờ mùa hè: {detail}")]
    AmbiguousLocalTime { detail: String },
    #[error("Không tìm thấy công việc")]
    NotFound,
    #[error("Lỗi cơ sở dữ liệu: {detail}")]
    Database { detail: String },
}

impl From<rusqlite::Error> for TaskError {
    fn from(value: rusqlite::Error) -> Self { Self::Database { detail: value.to_string() } }
}

#[derive(uniffi::Object)]
pub struct ScheduleManager { db: Mutex<Connection> }

const TASK_COLUMNS: &str = "id, title, notes, due_at_utc, time_zone, is_completed, created_at_utc, \
    sound_id, sound_name, repeat_mode, duration_days, interval_minutes, daily_times_csv, \
    (SELECT COUNT(*) FROM reminder_occurrences WHERE task_id=tasks.id)";

fn from_row(row: &Row<'_>) -> rusqlite::Result<Task> {
    Ok(Task { id: row.get(0)?, title: row.get(1)?, notes: row.get(2)?, due_at_utc: row.get(3)?,
        time_zone: row.get(4)?, is_completed: row.get(5)?, created_at_utc: row.get(6)?,
        sound_id: row.get(7)?, sound_name: row.get(8)?, repeat_mode: row.get(9)?,
        duration_days: row.get(10)?, interval_minutes: row.get(11)?,
        daily_times_csv: row.get(12)?, reminder_count: row.get(13)? })
}

fn validation(detail: impl Into<String>) -> TaskError { TaskError::Validation { detail: detail.into() } }

fn validated_details(title: &str, notes: &str) -> Result<String, TaskError> {
    let title = title.trim();
    if title.is_empty() || title.chars().count() > MAX_TITLE_CHARS {
        return Err(validation(format!("Tiêu đề phải từ 1 đến {MAX_TITLE_CHARS} ký tự")));
    }
    if notes.chars().count() > MAX_NOTES_CHARS {
        return Err(validation(format!("Ghi chú không được quá {MAX_NOTES_CHARS} ký tự")));
    }
    Ok(title.to_owned())
}

fn parse_datetime(local: &str, zone: &str) -> Result<(NaiveDateTime, Tz), TaskError> {
    let tz: Tz = zone.parse().map_err(|_| TaskError::InvalidTimeZone { detail: zone.to_owned() })?;
    let naive = NaiveDateTime::parse_from_str(local, "%Y-%m-%dT%H:%M:%S")
        .map_err(|_| validation("Ngày giờ phải có dạng YYYY-MM-DDTHH:MM:SS"))?;
    Ok((naive, tz))
}

/// For the starting time reject ambiguous/nonexistent wall times. For later DST transitions,
/// skip nonexistent slots; if ambiguous, choose the first occurrence to avoid duplicate alerts.
fn strict_local_epoch(naive: NaiveDateTime, tz: Tz) -> Result<i64, TaskError> {
    match tz.from_local_datetime(&naive) {
        LocalResult::Single(dt) => Ok(dt.timestamp()),
        LocalResult::None => Err(TaskError::NonexistentLocalTime { detail: naive.to_string() }),
        LocalResult::Ambiguous(_, _) => Err(TaskError::AmbiguousLocalTime { detail: naive.to_string() }),
    }
}

fn generate_times(due_local: &str, zone: &str, opts: &TaskOptions) -> Result<Vec<i64>, TaskError> {
    let (start_local, tz) = parse_datetime(due_local, zone)?;
    let first = strict_local_epoch(start_local, tz)?;
    if first <= Utc::now().timestamp() { return Err(validation("Thời gian bắt đầu phải ở tương lai")); }
    if !(1..=7).contains(&opts.duration_days) { return Err(validation("Số ngày phải từ 1 đến 7")); }
    if opts.sound_id.len() > 100 || !opts.sound_id.chars().all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '_' || c == '-') {
        return Err(validation("Mã âm thanh không hợp lệ"));
    }
    if opts.sound_name.chars().count() > 150 { return Err(validation("Tên âm thanh quá dài")); }

    let mut result = Vec::new();
    match opts.repeat_mode.as_str() {
        "once" => result.push(first),
        "interval" => {
            if !(30..=1440).contains(&opts.interval_minutes) {
                return Err(validation("Khoảng lặp phải từ 30 đến 1440 phút"));
            }
            let step = i64::from(opts.interval_minutes) * 60;
            let end = first + i64::from(opts.duration_days) * 86_400;
            let mut ts = first;
            while ts < end && result.len() <= MAX_OCCURRENCES {
                result.push(ts);
                ts += step;
            }
        }
        "slots" => {
            let mut times = Vec::new();
            let mut seen = HashSet::new();
            for part in opts.daily_times_csv.split(',').filter(|s| !s.trim().is_empty()) {
                let time = NaiveTime::parse_from_str(part.trim(), "%H:%M")
                    .map_err(|_| validation("Giờ lặp cần dạng HH:mm, ví dụ 08:00,12:00"))?;
                if seen.insert(time) { times.push(time); }
            }
            if times.is_empty() || times.len() > 12 { return Err(validation("Chọn từ 1 đến 12 khung giờ mỗi ngày")); }
            for offset in 0..opts.duration_days {
                let date = start_local.date().checked_add_signed(ChronoDuration::days(i64::from(offset)))
                    .ok_or_else(|| validation("Ngày không hợp lệ"))?;
                for time in &times {
                    let local = date.and_time(*time);
                    if local < start_local { continue; }
                    let when = match tz.from_local_datetime(&local) {
                        LocalResult::Single(dt) => Some(dt.timestamp()),
                        LocalResult::Ambiguous(a, b) => Some(a.timestamp().min(b.timestamp())),
                        LocalResult::None => None,
                    };
                    if let Some(ts) = when { if ts > Utc::now().timestamp() { result.push(ts); } }
                }
            }
        }
        _ => return Err(validation("Chế độ lặp không hợp lệ")),
    }
    result.sort_unstable();
    result.dedup();
    if result.is_empty() { return Err(validation("Không có lần nhắc nào sau thời điểm bắt đầu; hãy chọn giờ muộn hơn")); }
    if result.len() > MAX_OCCURRENCES { return Err(validation("Tối đa 512 lần nhắc mỗi công việc")); }
    Ok(result)
}

fn write_occurrences(tx: &Transaction<'_>, task_id: &str, times: &[i64]) -> Result<(), TaskError> {
    tx.execute("DELETE FROM reminder_occurrences WHERE task_id=?1", [task_id])?;
    for fire_at in times {
        tx.execute("INSERT INTO reminder_occurrences (id, task_id, fire_at_utc) VALUES (?1,?2,?3)",
            params![format!("{task_id}:{fire_at}"), task_id, fire_at])?;
    }
    Ok(())
}

fn migrate(conn: &Connection) -> Result<(), TaskError> {
    conn.execute_batch(
        "PRAGMA foreign_keys=ON;
         CREATE TABLE IF NOT EXISTS tasks (
           id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, notes TEXT NOT NULL DEFAULT '',
           due_at_utc INTEGER NOT NULL, time_zone TEXT NOT NULL,
           is_completed INTEGER NOT NULL DEFAULT 0 CHECK (is_completed IN (0,1)),
           created_at_utc INTEGER NOT NULL
         );"
    )?;
    let mut stmt = conn.prepare("PRAGMA table_info(tasks)")?;
    let fields: HashSet<String> = stmt.query_map([], |row| row.get::<_, String>(1))?
        .collect::<rusqlite::Result<_>>()?;
    let additions = [
        ("sound_id", "TEXT NOT NULL DEFAULT ''"),
        ("sound_name", "TEXT NOT NULL DEFAULT ''"),
        ("repeat_mode", "TEXT NOT NULL DEFAULT 'once'"),
        ("duration_days", "INTEGER NOT NULL DEFAULT 1"),
        ("interval_minutes", "INTEGER NOT NULL DEFAULT 0"),
        ("daily_times_csv", "TEXT NOT NULL DEFAULT ''"),
    ];
    for (name, data_type) in additions {
        if !fields.contains(name) { conn.execute_batch(&format!("ALTER TABLE tasks ADD COLUMN {name} {data_type}"))?; }
    }
    conn.execute_batch(
        "CREATE INDEX IF NOT EXISTS idx_tasks_due ON tasks(is_completed, due_at_utc);
         CREATE TABLE IF NOT EXISTS reminder_occurrences (
           id TEXT PRIMARY KEY NOT NULL,
           task_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
           fire_at_utc INTEGER NOT NULL,
           delivered INTEGER NOT NULL DEFAULT 0 CHECK (delivered IN (0,1)),
           UNIQUE(task_id, fire_at_utc)
         );
         CREATE INDEX IF NOT EXISTS idx_occurrences_pending ON reminder_occurrences(task_id, delivered, fire_at_utc);
         INSERT OR IGNORE INTO reminder_occurrences(id, task_id, fire_at_utc)
           SELECT id || ':' || due_at_utc, id, due_at_utc FROM tasks
           WHERE NOT EXISTS(SELECT 1 FROM reminder_occurrences WHERE task_id=tasks.id);
         PRAGMA user_version=2;"
    )?;
    Ok(())
}

impl ScheduleManager {
    fn lock(&self) -> Result<MutexGuard<'_, Connection>, TaskError> {
        self.db.lock().map_err(|_| TaskError::Database { detail: "Không thể khóa SQLite".into() })
    }
}

#[uniffi::export]
impl ScheduleManager {
    #[uniffi::constructor]
    pub fn new(database_path: String) -> Result<Arc<Self>, TaskError> {
        if database_path.trim().is_empty() { return Err(validation("Thiếu đường dẫn cơ sở dữ liệu")); }
        let conn = Connection::open_with_flags(database_path,
            OpenFlags::SQLITE_OPEN_READ_WRITE | OpenFlags::SQLITE_OPEN_CREATE | OpenFlags::SQLITE_OPEN_FULL_MUTEX)?;
        conn.busy_timeout(Duration::from_secs(3))?;
        conn.pragma_update(None, "journal_mode", "WAL")?;
        conn.pragma_update(None, "synchronous", "NORMAL")?;
        migrate(&conn)?;
        Ok(Arc::new(Self { db: Mutex::new(conn) }))
    }

    /// Original API is retained so future clients do not break.
    pub fn create_task(&self, title: String, notes: String, due_local: String, time_zone: String)
        -> Result<Task, TaskError> {
        self.create_task_with_options(title, notes, due_local, time_zone, TaskOptions::once())
    }

    pub fn create_task_with_options(&self, title: String, notes: String, due_local: String,
        time_zone: String, options: TaskOptions) -> Result<Task, TaskError> {
        let title = validated_details(&title, &notes)?;
        let times = generate_times(&due_local, &time_zone, &options)?;
        let due_at = times[0];
        let now = Utc::now().timestamp();
        let mut db = self.lock()?;
        let tx = db.transaction()?;
        tx.execute(
            "INSERT INTO tasks (id,title,notes,due_at_utc,time_zone,created_at_utc,sound_id,sound_name,repeat_mode,duration_days,interval_minutes,daily_times_csv)
             VALUES (lower(hex(randomblob(16))),?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11)",
            params![title,notes,due_at,time_zone,now,options.sound_id,options.sound_name,
                options.repeat_mode,options.duration_days,options.interval_minutes,options.daily_times_csv]
        )?;
        let row_id = tx.last_insert_rowid();
        let id: String = tx.query_row("SELECT id FROM tasks WHERE rowid=?1", [row_id], |row| row.get(0))?;
        write_occurrences(&tx, &id, &times)?;
        let task = tx.query_row(&format!("SELECT {TASK_COLUMNS} FROM tasks WHERE id=?1"), [id], from_row)?;
        tx.commit()?;
        Ok(task)
    }

    pub fn list_tasks(&self) -> Result<Vec<Task>, TaskError> {
        let db = self.lock()?;
        let mut statement = db.prepare(&format!("SELECT {TASK_COLUMNS} FROM tasks ORDER BY is_completed, due_at_utc, created_at_utc"))?;
        let rows = statement.query_map([], from_row)?;
        rows.collect::<rusqlite::Result<Vec<_>>>().map_err(Into::into)
    }

    pub fn update_task(&self, task_id: String, title: String, notes: String, due_local: String,
        time_zone: String) -> Result<(), TaskError> {
        self.update_task_with_options(task_id, title, notes, due_local, time_zone, TaskOptions::once())
    }

    pub fn update_task_with_options(&self, task_id: String, title: String, notes: String,
        due_local: String, time_zone: String, options: TaskOptions) -> Result<(), TaskError> {
        let title = validated_details(&title, &notes)?;
        let times = generate_times(&due_local, &time_zone, &options)?;
        let mut db = self.lock()?;
        let tx = db.transaction()?;
        let changed = tx.execute(
            "UPDATE tasks SET title=?1, notes=?2, due_at_utc=?3, time_zone=?4,
             sound_id=?5, sound_name=?6, repeat_mode=?7, duration_days=?8, interval_minutes=?9,
             daily_times_csv=?10 WHERE id=?11",
            params![title,notes,times[0],time_zone,options.sound_id,options.sound_name,
                options.repeat_mode,options.duration_days,options.interval_minutes,
                options.daily_times_csv,task_id]
        )?;
        if changed == 0 { return Err(TaskError::NotFound); }
        write_occurrences(&tx, &task_id, &times)?;
        tx.commit()?;
        Ok(())
    }

    /// Returns the next future, not-yet-delivered reminder for each active task.
    pub fn list_next_occurrences(&self, now_utc: i64) -> Result<Vec<ReminderOccurrence>, TaskError> {
        let db = self.lock()?;
        let mut statement = db.prepare(
            "SELECT o.id, o.task_id, o.fire_at_utc FROM reminder_occurrences o
             INNER JOIN tasks t ON o.task_id=t.id
             WHERE t.is_completed=0 AND o.delivered=0 AND o.fire_at_utc>=?1
               AND o.fire_at_utc=(SELECT MIN(i.fire_at_utc) FROM reminder_occurrences i
                 WHERE i.task_id=o.task_id AND i.delivered=0 AND i.fire_at_utc>=?1)
             ORDER BY o.fire_at_utc LIMIT 256"
        )?;
        let rows = statement.query_map([now_utc], |row| Ok(ReminderOccurrence {
            id: row.get(0)?, task_id: row.get(1)?, fire_at_utc: row.get(2)?,
        }))?;
        rows.collect::<rusqlite::Result<Vec<_>>>().map_err(Into::into)
    }

    /// Atomically acknowledge a due occurrence. Prevents duplicate broadcasts.
    pub fn claim_occurrence(&self, occurrence_id: String, now_utc: i64) -> Result<Option<Task>, TaskError> {
        let mut db = self.lock()?;
        let tx = db.transaction()?;
        let changed = tx.execute(
            "UPDATE reminder_occurrences SET delivered=1
             WHERE id=?1 AND delivered=0 AND fire_at_utc<=?2
             AND EXISTS(SELECT 1 FROM tasks WHERE id=task_id AND is_completed=0)",
            params![occurrence_id, now_utc + 60],
        )?;
        if changed == 0 { return Ok(None); }
        let task = tx.query_row(
            &format!("SELECT {TASK_COLUMNS} FROM tasks WHERE id=(SELECT task_id FROM reminder_occurrences WHERE id=?1)"),
            [occurrence_id], from_row
        )?;
        tx.commit()?;
        Ok(Some(task))
    }

    pub fn set_completed(&self, task_id: String, completed: bool) -> Result<(), TaskError> {
        let db = self.lock()?;
        let changed = db.execute("UPDATE tasks SET is_completed=?1 WHERE id=?2", params![completed, task_id])?;
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
    fn options(mode: &str) -> TaskOptions {
        TaskOptions { sound_id: "sample.mp3".into(), sound_name: "Chuông".into(),
            repeat_mode: mode.into(), duration_days: 7, interval_minutes: 240,
            daily_times_csv: "08:00,12:00,20:00".into() }
    }
    #[test]
    fn migrate_and_crud() {
        let manager = ScheduleManager::new(":memory:".into()).unwrap();
        let task = manager.create_task_with_options(" Uống nước ".into(), "".into(),
            "2099-07-01T08:00:00".into(), "Asia/Ho_Chi_Minh".into(), options("slots")).unwrap();
        assert_eq!(task.title, "Uống nước");
        assert_eq!(task.reminder_count, 21);
        let next = manager.list_next_occurrences(Utc::now().timestamp()).unwrap();
        assert_eq!(next.len(), 1);
        manager.set_completed(task.id.clone(), true).unwrap();
        assert!(manager.list_next_occurrences(Utc::now().timestamp()).unwrap().is_empty());
        manager.delete_task(task.id).unwrap();
        assert!(manager.list_tasks().unwrap().is_empty());
    }
    #[test]
    fn interval_week_is_42_notifications() {
        let result = generate_times("2099-05-05T09:00:00", "Asia/Ho_Chi_Minh", &options("interval")).unwrap();
        assert_eq!(result.len(), 42);
    }
    #[test]
    fn spring_dst_skips_nonexistent_slot() {
        let mut opts = options("slots");
        opts.duration_days = 3;
        opts.daily_times_csv = "02:30".into();
        let result = generate_times("2027-03-13T02:00:00", "America/New_York", &opts).unwrap();
        // 2027-03-14 02:30 is skipped due to the spring-forward DST gap.
        assert_eq!(result.len(), 2);
    }
    #[test]
    fn ambiguous_dst_start_is_rejected() {
        let (naive, tz) = parse_datetime("2026-11-01T01:30:00", "America/New_York").unwrap();
        assert!(matches!(strict_local_epoch(naive, tz), Err(TaskError::AmbiguousLocalTime { .. })));
    }
}