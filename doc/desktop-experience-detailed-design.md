# Clender 桌面体验修复与今日悬浮窗详细设计

## 1. 配置契约

`DEFAULT_CONFIG` 新增：

```python
{
    "floating_window_enabled": False,
    "floating_window_opacity": 90,
    "floating_window_start_time": "08:00",
    "floating_window_end_time": "22:00",
    "floating_window_geometry": None,
}
```

`config.load_config()`：文件不存在、坏 JSON 或非对象时使用默认值；合法对象与默认值合并，不迁移/读取凭据。`config.save_config()` 将传入字典原子写入，包括 `api_key`。`secret_store.py` 和其专项测试删除；pywin32 仍因快捷方式构建保留。

## 2. 单实例接口

```python
class SingleInstanceCoordinator(QObject):
    activation_requested = pyqtSignal()

    def __init__(self, service_name: str | None = None, parent=None): ...
    def acquire(self, timeout_ms: int = 500) -> bool: ...
    def close(self) -> None: ...
```

- 默认服务名包含应用名和当前 Windows 用户的稳定摘要。
- `acquire()` 先尝试连接；成功则发送 `activate` 并返回 `False`。
- 连接失败则尝试监听；监听失败后再次连接，只有仍不可通信时才清理陈旧端点并重试一次。
- 主实例逐个读取 pending connection；仅合法 `activate` 发射信号。
- `main.main()` 创建 QApplication 和应用元数据后立即 acquire；secondary 返回 0，不配置日志/数据库。
- `MainWindow.activate_existing_instance()` 优先置前 active modal；否则保留最大化状态，清除最小化并 `show/raise_/activateWindow`。

## 3. Thinking 视觉锚点

- 展开键使用 `(conversation_id, think_index)`。
- HTML anchor 同时提供唯一 name/href。
- `_render_conv_messages(scroll_mode="bottom" | "preserve", anchor=None)`：
  - bottom 用于初始、新消息、切换对话；
  - preserve 在重绘前记录点击 anchor 的 document y 与 scrollbar value，重绘后计算新 anchor y，用差值恢复 scrollbar。
- 对无法定位的 anchor 回退到原 scrollbar value；数值钳制到合法范围。
- toggle URL 只接受非负十进制索引且必须存在。

## 4. 日历布局与命中契约

`calendar_logic` 为每个 block 增加：

```python
{
    "lane": int,
    "lane_count": int,
    "cluster_id": int,
    "overlap_ranges": list[tuple[float, float]],
}
```

算法：按日期列和 top 排序；相邻不算重叠；扫描活动集合分配最小空闲 lane；同一连通重叠簇共享最大并发 `lane_count`。继续保留 pair overlap ranges，供仅在实际重叠纵向区间绘制边缘纹理。

Canvas：

```python
class WeekCanvas(QFrame):
    event_activated = pyqtSignal(object)  # tuple[int, ...]

class DayCanvas(QFrame):
    event_activated = pyqtSignal(object)
```

- 单 lane 使用整栏；多 lane 以间距分栏。
- 宽度足够且高度满足字体度量时绘制标题；再有一行空间才绘制时间。
- reminder/极短块只绘制高对比 marker，最小点击高度独立于视觉高度。
- overlap ranges 仅在块右缘窄带绘制纹理，文字矩形扣除该窄带。
- lane 宽度低于最小点击宽度时，溢出 lane 合并为摘要 hit region，携带多个 ID；UI 弹出选择列表。
- `mousePressEvent()` 只响应左键，按逆绘制顺序命中，发射事件 ID tuple。

`CalendarWidget.event_activated` 转发 Canvas 信号。`MainWindow` 使用 `EventService.get_event_by_id()` 打开共享详情；多个 ID 先展示选择对话框。

## 5. 时间范围查询

新增：

```python
database.get_events_overlapping_range(start: datetime, end: datetime) -> list[Event]
EventService.get_events_overlapping_range(start: datetime, end: datetime) -> list[Event]
```

查询语义：

- reminder：`start_time >= start AND start_time < end`；
- timespan：`start_time < end AND end_time > start`；
- 按 start_time、id 排序；非法旧数据不会由 UI 绘制，业务层不修改旧数据。

## 6. 悬浮设置纯逻辑

```python
@dataclass(frozen=True)
class FloatingWindowSettings:
    enabled: bool
    opacity_percent: int
    start_time: time
    end_time: time
    geometry: tuple[int, int, int, int] | None

    @classmethod
    def from_config(cls, value: dict) -> "FloatingWindowSettings": ...

def day_window(target: date, settings: FloatingWindowSettings) -> tuple[datetime, datetime]: ...
def format_event_time(event: Event) -> str: ...
```

- 非 bool enable、bool opacity、越界数值、坏时间、起止相等/倒序均回退对应默认值。
- 时间区间为同日 `[start, end)`，本版本不允许跨午夜的设置范围。

## 7. 悬浮窗与设置 UI

`AppSettingsDialog` 承接原内联应用设置，并新增开关、透明度滑块、QTimeEdit 起止时间。保存先校验时间，再调用 `config.save_config()`；失败留在窗口；成功发射配置副本。

`DailyFloatingWindow`：

- 顶层 `Qt.Tool | Qt.WindowStaysOnTopHint`，由 MainWindow 持有，不以主窗口作 Qt 父对象。
- 内含日期标题、事件列表、空状态；列表 item 的 UserRole 保存 event ID。
- `apply_settings()` 更新透明度并显示/隐藏；`refresh()` 通过 EventService 相交查询更新列表。
- item click 发射 ID；MainWindow 打开详情。
- move/resize 后用单次 QTimer 防抖发射 geometry_changed；MainWindow 原子写配置。
- closeEvent 只隐藏并发射 visibility_change_requested(False)，不退出应用、不永久关闭 enabled。
- 每 60 秒检查 `date.today()`；日期变化或睡眠后第一次 tick 立即刷新。

`EventDetailDialog` 只接收 Event，展示类型、标题、起止/预计时长、描述，无保存按钮。

## 8. 主窗口编排

- 构造时创建唯一 DailyFloatingWindow 并应用配置。
- `_refresh_all()` 最后刷新悬浮窗；因此手工与 AI `data_changed` 都自动刷新。
- 连接 CalendarWidget/DailyFloatingWindow 的 event activation 到共享详情方法。
- 设置保存成功后应用 close-to-tray 与悬浮配置，不重复创建窗口。
- 托盘增加“显示/隐藏今日悬浮窗”；退出时显式停止/关闭悬浮窗。
- 主题切换时对悬浮窗和已打开详情应用主题。

## 9. 测试矩阵

| 模块 | 正常 | 边界 | 异常 | 回归 |
|---|---|---|---|---|
| JSON Key | 非空/空 Key 往返 | Unicode、长值、缺字段 | 坏 JSON、replace 失败 | 主题/托盘保存不丢 Key、导入无副作用 |
| Thinking | 中间长内容向下展开 | 首末、多会话、超长 | 坏/负/越界 URL | 新消息滚底、主题重绘 |
| 单实例 | primary/secondary/activate | 最小化/最大化/托盘/modal | timeout、陈旧服务、竞争 | secondary 不初始化数据/日志/UI |
| 日历 | 2/N lane、纹理、点击 | 00:00、23:59、短提醒、长标题、窄宽 | 坏时间/负时长 | 相邻不重叠、Light/Dark、缩放 |
| 查询 | reminder/timespan 相交 | 跨日、端点相等 | 空范围/倒序 | 临时旧库、事务与连接关闭 |
| 悬浮 | 启用、刷新、详情 | 空列表、透明度边界、多屏几何 | 坏配置、保存失败 | 主程序每次数据变化、午夜、托盘、主题 |
| 发布 | 完整构建、首实例 | 双实例 exe | 第二实例超时诊断 | dist/data 摘要不变、无秘密提交 |

## 10. 回滚与兼容

- 所有新配置有默认值，旧 JSON 直接兼容；回滚版本会忽略新字段。
- 无 schema 字段迁移，仅新增查询；真实数据库不改写。
- 单实例模块可从 main 入口断开以回退；悬浮窗默认关闭。
- Git 回滚只恢复工程文件；`data/` 与 `dist/data/` 不参与提交或回滚。
