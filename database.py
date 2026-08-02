"""
数据库层 - SQLite
提供 events 表的 CRUD 操作，返回 Event 模型对象
init_db() 需显式调用，不在模块导入时自动执行
"""
import sqlite3
from contextlib import closing
from datetime import date
from typing import Optional, List
import os

from config import DB_PATH, APP_DATA_DIR
from models import Event, EventType
from logger import get_logger

_log = get_logger(__name__)

# update_event 允许的字段白名单
_ALLOWED_UPDATE_FIELDS = frozenset({'title', 'start_time', 'end_time',
                                     'description', 'estimated_duration'})


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
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        ''')
        columns = {row[1] for row in cursor.execute('PRAGMA table_info(events)')}
        if 'estimated_duration' not in columns:
            cursor.execute(
                'ALTER TABLE events ADD COLUMN estimated_duration INTEGER DEFAULT 0'
            )
        cursor.execute(
            'CREATE INDEX IF NOT EXISTS idx_events_start_time ON events(start_time)'
        )
    _log.debug("数据库初始化完成")


def add_event(event_type: str, title: str, start_time: str,
              end_time: Optional[str] = None, description: str = '',
              estimated_duration: int = 0) -> int:
    """添加事件，返回新插入的 event_id"""
    with closing(get_connection()) as conn, conn:
        cursor = conn.cursor()
        cursor.execute(
            'INSERT INTO events (event_type, title, start_time, end_time, description, estimated_duration) '
            'VALUES (?, ?, ?, ?, ?, ?)',
            (event_type, title, start_time, end_time, description, estimated_duration)
        )
        return cursor.lastrowid


def update_event(event_id: int, **fields) -> int:
    """更新事件字段，返回受影响行数
    
    仅允许更新白名单内的字段：title, start_time, end_time, description, estimated_duration。
    未知字段名将被静默过滤。
    """
    safe_fields = {
        key: value for key, value in fields.items()
        if key in _ALLOWED_UPDATE_FIELDS and (value is not None or key == 'end_time')
    }
    if not safe_fields:
        return 0

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
        cursor.execute('DELETE FROM events WHERE id = ?', (event_id,))
        return cursor.rowcount > 0


def get_events_by_date(target_date: date) -> List[Event]:
    """获取指定日期的所有事件"""
    date_str = target_date.isoformat()
    with closing(get_connection()) as conn:
        cursor = conn.cursor()
        cursor.execute(
            "SELECT * FROM events WHERE start_time LIKE ? ORDER BY start_time ASC",
            (f'{date_str}%',)
        )
        rows = cursor.fetchall()
    return [Event.from_row(dict(row)) for row in rows]


def get_all_events() -> List[Event]:
    """获取所有事件"""
    with closing(get_connection()) as conn:
        cursor = conn.cursor()
        cursor.execute('SELECT * FROM events ORDER BY start_time ASC')
        rows = cursor.fetchall()
    return [Event.from_row(dict(row)) for row in rows]


def get_events_date_range(start_date: date, end_date: date) -> List[Event]:
    """获取日期区间内的事件"""
    with closing(get_connection()) as conn:
        cursor = conn.cursor()
        cursor.execute(
            'SELECT * FROM events WHERE start_time >= ? AND start_time <= ? ORDER BY start_time ASC',
            (start_date.isoformat() + ' 00:00', end_date.isoformat() + ' 23:59')
        )
        rows = cursor.fetchall()
    return [Event.from_row(dict(row)) for row in rows]


def get_event_by_id(event_id: int) -> Optional[Event]:
    """按 ID 获取单个事件，不存在返回 None"""
    with closing(get_connection()) as conn:
        cursor = conn.cursor()
        cursor.execute('SELECT * FROM events WHERE id = ?', (event_id,))
        row = cursor.fetchone()
    return Event.from_row(dict(row)) if row else None


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
