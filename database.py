"""
数据库层 - SQLite
提供 events 表的 CRUD 操作，返回 Event 模型对象
init_db() 需显式调用，不在模块导入时自动执行
"""
import json
import sqlite3
from contextlib import closing
from datetime import date, datetime, timezone
from typing import Optional, List
import os
from uuid import uuid4

from config import DB_PATH, APP_DATA_DIR
from models import Event, EventType
from logger import get_logger

_log = get_logger(__name__)

# update_event 允许的字段白名单
_ALLOWED_UPDATE_FIELDS = frozenset({'event_type', 'title', 'start_time',
                                     'end_time', 'description',
                                     'estimated_duration'})

_SYNC_RECORD_FIELDS = (
    'sync_uid', 'event_type', 'title', 'start_time', 'end_time',
    'description', 'estimated_duration', 'created_at', 'updated_at',
    'deleted_at',
)


def _utc_now_text() -> str:
    """Return a sortable UTC RFC3339 timestamp used by sync metadata."""
    return datetime.now(timezone.utc).isoformat(timespec="microseconds").replace(
        "+00:00", "Z"
    )


def _normalize_created_at(value, fallback: str) -> str:
    """Normalize legacy SQLite timestamps for the remote sync contract."""
    if isinstance(value, str):
        text = value.strip()
        if text.endswith("Z"):
            return text
        try:
            parsed = datetime.strptime(text, "%Y-%m-%d %H:%M:%S")
            return parsed.replace(tzinfo=timezone.utc).isoformat(
                timespec="microseconds"
            ).replace("+00:00", "Z")
        except ValueError:
            pass
    return fallback


def _sync_record_from_row(row) -> dict:
    data = dict(row)
    return {
        "sync_uid": data["sync_uid"],
        "event_type": data["event_type"],
        "title": data["title"],
        "start_time": data["start_time"],
        "end_time": data.get("end_time"),
        "description": data.get("description", "") or "",
        "estimated_duration": data.get("estimated_duration", 0) or 0,
        "created_at": _normalize_created_at(
            data.get("created_at"), data["updated_at"]
        ),
        "updated_at": data["updated_at"],
        "deleted_at": data.get("deleted_at"),
    }


def _sync_record_key(record: dict) -> tuple[str, str]:
    canonical = json.dumps(
        {field: record.get(field) for field in _SYNC_RECORD_FIELDS},
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return record["updated_at"], canonical


def _visible_signature(rows) -> tuple:
    visible = []
    for row in rows:
        data = dict(row)
        if data.get("deleted_at") is not None:
            continue
        visible.append((
            data["sync_uid"], data["event_type"], data["title"],
            data["start_time"], data.get("end_time"),
            data.get("description", "") or "",
            data.get("estimated_duration", 0) or 0,
        ))
    return tuple(sorted(visible))


def get_connection():
    """获取数据库连接，row_factory = sqlite3.Row"""
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def init_db() -> None:
    """初始化数据库：创建 events 表（如果不存在）并补全字段
    
    必须在应用启动时显式调用。
    """
    parent = os.path.dirname(DB_PATH)
    if parent:
        os.makedirs(parent, exist_ok=True)
    with closing(get_connection()) as conn, conn:
        cursor = conn.cursor()
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_type TEXT NOT NULL CHECK(event_type IN ('reminder', 'timespan')),
                title TEXT NOT NULL,
                start_time TEXT NOT NULL,
                end_time TEXT,
                description TEXT DEFAULT '',
                estimated_duration INTEGER DEFAULT 0,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                sync_uid TEXT,
                updated_at TEXT,
                deleted_at TEXT
            )
        ''')
        columns = {row[1] for row in cursor.execute('PRAGMA table_info(events)')}
        if 'estimated_duration' not in columns:
            cursor.execute(
                'ALTER TABLE events ADD COLUMN estimated_duration INTEGER DEFAULT 0'
            )
        if 'sync_uid' not in columns:
            cursor.execute('ALTER TABLE events ADD COLUMN sync_uid TEXT')
        if 'updated_at' not in columns:
            cursor.execute('ALTER TABLE events ADD COLUMN updated_at TEXT')
        if 'deleted_at' not in columns:
            cursor.execute('ALTER TABLE events ADD COLUMN deleted_at TEXT')

        now = _utc_now_text()
        missing_rows = cursor.execute(
            "SELECT id, sync_uid, updated_at FROM events "
            "WHERE sync_uid IS NULL OR sync_uid = '' "
            "OR updated_at IS NULL OR updated_at = ''"
        ).fetchall()
        for row in missing_rows:
            event_id, sync_uid, updated_at = row
            cursor.execute(
                'UPDATE events SET sync_uid = ?, updated_at = ? WHERE id = ?',
                (sync_uid or uuid4().hex, updated_at or now, event_id),
            )
        cursor.execute(
            'CREATE INDEX IF NOT EXISTS idx_events_start_time ON events(start_time)'
        )
        cursor.execute(
            'CREATE UNIQUE INDEX IF NOT EXISTS idx_events_sync_uid ON events(sync_uid)'
        )
    _log.debug("数据库初始化完成")


def add_event(event_type: str, title: str, start_time: str,
              end_time: Optional[str] = None, description: str = '',
              estimated_duration: int = 0) -> int:
    """添加事件，返回新插入的 event_id"""
    sync_uid = uuid4().hex
    now = _utc_now_text()
    with closing(get_connection()) as conn, conn:
        cursor = conn.cursor()
        cursor.execute(
            'INSERT INTO events '
            '(event_type, title, start_time, end_time, description, '
            'estimated_duration, created_at, sync_uid, updated_at, deleted_at) '
            'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)',
            (event_type, title, start_time, end_time, description,
             estimated_duration, now, sync_uid, now)
        )
        return cursor.lastrowid


def update_event(event_id: int, **fields) -> int:
    """更新事件字段，返回受影响行数
    
    仅允许更新白名单内的字段：event_type, title, start_time, end_time,
    description, estimated_duration。
    未知字段名将被静默过滤。
    """
    safe_fields = {
        key: value for key, value in fields.items()
        if key in _ALLOWED_UPDATE_FIELDS and (value is not None or key == 'end_time')
    }
    if not safe_fields:
        return 0

    safe_fields['updated_at'] = _utc_now_text()
    set_clauses = [f'{k} = ?' for k in safe_fields]
    values = list(safe_fields.values())
    values.append(event_id)

    sql = f'UPDATE events SET {", ".join(set_clauses)} WHERE id = ?'
    with closing(get_connection()) as conn, conn:
        cursor = conn.cursor()
        cursor.execute(sql, values)
        return cursor.rowcount


def delete_event(event_id: int) -> bool:
    """删除事件，返回是否成功删除"""
    with closing(get_connection()) as conn, conn:
        cursor = conn.cursor()
        now = _utc_now_text()
        cursor.execute(
            'UPDATE events SET deleted_at = ?, updated_at = ? '
            'WHERE id = ? AND deleted_at IS NULL',
            (now, now, event_id),
        )
        return cursor.rowcount > 0


def get_events_by_date(target_date: date) -> List[Event]:
    """获取指定日期的所有事件"""
    date_str = target_date.isoformat()
    with closing(get_connection()) as conn:
        cursor = conn.cursor()
        cursor.execute(
            "SELECT * FROM events WHERE deleted_at IS NULL "
            "AND start_time LIKE ? ORDER BY start_time ASC",
            (f'{date_str}%',)
        )
        rows = cursor.fetchall()
    return [Event.from_row(dict(row)) for row in rows]


def get_all_events() -> List[Event]:
    """获取所有事件"""
    with closing(get_connection()) as conn:
        cursor = conn.cursor()
        cursor.execute(
            'SELECT * FROM events WHERE deleted_at IS NULL ORDER BY start_time ASC'
        )
        rows = cursor.fetchall()
    return [Event.from_row(dict(row)) for row in rows]


def get_events_date_range(start_date: date, end_date: date) -> List[Event]:
    """获取日期区间内的事件"""
    with closing(get_connection()) as conn:
        cursor = conn.cursor()
        cursor.execute(
            'SELECT * FROM events WHERE deleted_at IS NULL '
            'AND start_time >= ? AND start_time <= ? ORDER BY start_time ASC',
            (start_date.isoformat() + ' 00:00', end_date.isoformat() + ' 23:59')
        )
        rows = cursor.fetchall()
    return [Event.from_row(dict(row)) for row in rows]


def get_events_overlapping_range(start: datetime, end: datetime) -> List[Event]:
    """Return events intersecting the half-open datetime range ``[start, end)``."""
    start_text = start.strftime('%Y-%m-%d %H:%M')
    end_text = end.strftime('%Y-%m-%d %H:%M')
    with closing(get_connection()) as conn:
        cursor = conn.cursor()
        cursor.execute(
            '''
            SELECT * FROM events
            WHERE deleted_at IS NULL AND (
                (
                    event_type = 'reminder'
                    AND start_time >= ?
                    AND start_time < ?
                ) OR (
                    event_type = 'timespan'
                    AND start_time < ?
                    AND end_time > ?
                )
            )
            ORDER BY start_time ASC, id ASC
            ''',
            (start_text, end_text, end_text, start_text),
        )
        rows = cursor.fetchall()
    return [Event.from_row(dict(row)) for row in rows]


def get_event_by_id(event_id: int) -> Optional[Event]:
    """按 ID 获取单个事件，不存在返回 None"""
    with closing(get_connection()) as conn:
        cursor = conn.cursor()
        cursor.execute(
            'SELECT * FROM events WHERE id = ? AND deleted_at IS NULL',
            (event_id,),
        )
        row = cursor.fetchone()
    return Event.from_row(dict(row)) if row else None


def get_sync_records() -> list[dict]:
    """Return every event sync record, including deletion tombstones."""
    with closing(get_connection()) as conn:
        cursor = conn.cursor()
        cursor.execute('SELECT * FROM events ORDER BY sync_uid ASC')
        rows = cursor.fetchall()
    return [_sync_record_from_row(row) for row in rows]


def apply_sync_records(records: list[dict]) -> bool:
    """Apply validated sync winners atomically and report visible changes."""
    with closing(get_connection()) as conn, conn:
        cursor = conn.cursor()
        before_rows = cursor.execute('SELECT * FROM events').fetchall()
        before = _visible_signature(before_rows)
        current_by_uid = {
            row['sync_uid']: row for row in before_rows
        }

        for incoming in records:
            normalized = {field: incoming[field] for field in _SYNC_RECORD_FIELDS}
            current_row = current_by_uid.get(normalized['sync_uid'])
            if current_row is not None:
                current = _sync_record_from_row(current_row)
                if _sync_record_key(normalized) <= _sync_record_key(current):
                    continue
                cursor.execute(
                    '''
                    UPDATE events SET
                        event_type = ?, title = ?, start_time = ?, end_time = ?,
                        description = ?, estimated_duration = ?, created_at = ?,
                        updated_at = ?, deleted_at = ?
                    WHERE sync_uid = ?
                    ''',
                    (
                        normalized['event_type'], normalized['title'],
                        normalized['start_time'], normalized['end_time'],
                        normalized['description'], normalized['estimated_duration'],
                        normalized['created_at'], normalized['updated_at'],
                        normalized['deleted_at'], normalized['sync_uid'],
                    ),
                )
            else:
                cursor.execute(
                    '''
                    INSERT INTO events (
                        event_type, title, start_time, end_time, description,
                        estimated_duration, created_at, sync_uid, updated_at,
                        deleted_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ''',
                    (
                        normalized['event_type'], normalized['title'],
                        normalized['start_time'], normalized['end_time'],
                        normalized['description'], normalized['estimated_duration'],
                        normalized['created_at'], normalized['sync_uid'],
                        normalized['updated_at'], normalized['deleted_at'],
                    ),
                )
            current_by_uid[normalized['sync_uid']] = normalized

        after = _visible_signature(cursor.execute('SELECT * FROM events').fetchall())
        return before != after


# ── 配置持久化辅助 ──
SAMPLE_FLAG_FILE = os.path.join(APP_DATA_DIR, 'sample_loaded.flag')


def is_sample_loaded() -> bool:
    """检查是否已加载示例数据"""
    return os.path.exists(SAMPLE_FLAG_FILE)


def mark_sample_loaded() -> None:
    """标记示例数据已加载"""
    parent = os.path.dirname(SAMPLE_FLAG_FILE)
    if parent:
        os.makedirs(parent, exist_ok=True)
    with open(SAMPLE_FLAG_FILE, 'w', encoding='utf-8') as f:
        f.write('1')
