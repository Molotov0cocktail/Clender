"""
数据库层 - SQLite
提供 events 表的 CRUD 操作，返回 Event 模型对象
init_db() 需显式调用，不在模块导入时自动执行
"""
import sqlite3
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
    conn = get_connection()
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
    # 兼容旧数据：为已有表补充 estimated_duration 列
    try:
        cursor.execute('ALTER TABLE events ADD COLUMN estimated_duration INTEGER DEFAULT 0')
    except sqlite3.OperationalError:
        pass  # 字段已存在
    conn.commit()
    conn.close()
    _log.debug("数据库初始化完成")


def add_event(event_type: str, title: str, start_time: str,
              end_time: Optional[str] = None, description: str = '',
              estimated_duration: int = 0) -> int:
    """添加事件，返回新插入的 event_id"""
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute(
        'INSERT INTO events (event_type, title, start_time, end_time, description, estimated_duration) '
        'VALUES (?, ?, ?, ?, ?, ?)',
        (event_type, title, start_time, end_time, description, estimated_duration)
    )
    conn.commit()
    new_id = cursor.lastrowid
    conn.close()
    return new_id


def update_event(event_id: int, **fields) -> int:
    """更新事件字段，返回受影响行数
    
    仅允许更新白名单内的字段：title, start_time, end_time, description, estimated_duration。
    未知字段名将被静默过滤。
    """
    conn = get_connection()
    cursor = conn.cursor()

    # 白名单过滤
    safe_fields = {k: v for k, v in fields.items()
                   if k in _ALLOWED_UPDATE_FIELDS and v is not None}
    if not safe_fields:
        conn.close()
        return 0

    set_clauses = [f'{k} = ?' for k in safe_fields]
    values = list(safe_fields.values())
    values.append(event_id)

    sql = f'UPDATE events SET {", ".join(set_clauses)} WHERE id = ?'
    cursor.execute(sql, values)
    conn.commit()
    rowcount = cursor.rowcount
    conn.close()
    return rowcount


def delete_event(event_id: int) -> bool:
    """删除事件，返回是否成功删除"""
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute('DELETE FROM events WHERE id = ?', (event_id,))
    conn.commit()
    deleted = cursor.rowcount > 0
    conn.close()
    return deleted


def get_events_by_date(target_date: date) -> List[Event]:
    """获取指定日期的所有事件"""
    date_str = target_date.isoformat()
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute(
        "SELECT * FROM events WHERE start_time LIKE ? ORDER BY start_time ASC",
        (f'{date_str}%',)
    )
    rows = cursor.fetchall()
    conn.close()
    return [Event.from_row(dict(row)) for row in rows]


def get_all_events() -> List[Event]:
    """获取所有事件"""
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute('SELECT * FROM events ORDER BY start_time ASC')
    rows = cursor.fetchall()
    conn.close()
    return [Event.from_row(dict(row)) for row in rows]


def get_events_date_range(start_date: date, end_date: date) -> List[Event]:
    """获取日期区间内的事件"""
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute(
        'SELECT * FROM events WHERE start_time >= ? AND start_time <= ? ORDER BY start_time ASC',
        (start_date.isoformat() + ' 00:00', end_date.isoformat() + ' 23:59')
    )
    rows = cursor.fetchall()
    conn.close()
    return [Event.from_row(dict(row)) for row in rows]


def get_event_by_id(event_id: int) -> Optional[Event]:
    """按 ID 获取单个事件，不存在返回 None"""
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute('SELECT * FROM events WHERE id = ?', (event_id,))
    row = cursor.fetchone()
    conn.close()
    return Event.from_row(dict(row)) if row else None


# ── 配置持久化辅助 ──
SAMPLE_FLAG_FILE = os.path.join(APP_DATA_DIR, 'sample_loaded.flag')


def is_sample_loaded() -> bool:
    """检查是否已加载示例数据"""
    return os.path.exists(SAMPLE_FLAG_FILE)


def mark_sample_loaded() -> None:
    """标记示例数据已加载"""
    with open(SAMPLE_FLAG_FILE, 'w') as f:
        f.write('1')