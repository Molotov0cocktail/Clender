# Clender 悬浮快捷对话、双字号与日历间距详细设计

## 1. 配置与字号契约

`DEFAULT_CONFIG` 新增：

```python
{
    "app_font_size_px": 13,
    "floating_font_size_px": 13,
}
```

透明度合法范围调整为 0–100。旧 JSON 无新字段时由默认合并兼容；bool、非 int 和越界字号/透明度回退默认。pin 不进入配置。

新增 `typography.py`：

```python
MIN_FONT_PX = 8
MAX_FONT_PX = 20
DEFAULT_FONT_PX = 13

@dataclass(frozen=True)
class TypographyScale:
    caption_px: int
    secondary_px: int
    body_px: int
    control_px: int
    section_title_px: int
    page_title_px: int

def validate_font_size(value, default=DEFAULT_FONT_PX) -> int: ...
def build_scale(base_px: int) -> TypographyScale: ...
def app_scale_from_config(config: dict) -> TypographyScale: ...
def floating_scale_from_config(config: dict) -> TypographyScale: ...
def qfont_for(scale: TypographyScale, role: str) -> QFont: ...
```

所有角色最终值钳制 8–20。建议映射：caption=base-2、secondary=base-1、body/control=base、section=base+2、page=base+4。唯一字号数字只允许在此模块和默认配置中定义。

`theme_manager.apply_theme(app)` 在 palette 前应用 `app_scale.body_px` 的 `QFont.setPixelSize()`，全局 QSS 使用命名角色。组件内 QSS/HTML必须引用 scale，不得出现数字 `px` 字号。

## 2. 设置 UI

`AppSettingsDialog` 增加两行独立控件：

- 应用字号：QSlider + QSpinBox，8..20，singleStep=1。
- 悬浮窗字号：QSlider + QSpinBox，8..20，singleStep=1。

双向连接需阻止递归但保持同值；对象名唯一且稳定，便于测试。`_save()` 从控件读取准确整数并更新完整配置。IO 失败时不关闭、不发信号、现有字体不变。

## 3. 字号迁移与审计

迁移范围：`main.py`、`theme_manager.py`、`ui/calendar_widget.py`、`ui/canvas.py`、`ui/event_manager.py`、`ui/ai_chat_widget.py`、`ui/sidebar.py`、`ui/ai_settings.py`、`ui/app_settings.py`、`ui/daily_floating_window.py`、事件表单/详情的继承与固定尺寸冒烟。

- 删除 `QFont(..., 10)` point size，使用应用 scale 的 pixel size。
- EventManager 初始化与 `apply_theme()` 重复 QSS 合并为单一入口；标题/日期保存为命名属性，不再按文本猜控件。
- AI 富文本 Thinking 链接/预览字号由 scale 生成；主题或字号重绘继续保持 anchor。
- 固定 24/26/30px 按钮与 14px token bar 改为 `fontMetrics/sizeHint` 决定的最小尺寸，或证明 20px 不裁剪。
- FloatingWindow 强制使用 floating scale 覆盖应用继承字体。

静态测试扫描生产 `.py`：除 `typography.py`/`constants.py` 外，不允许 `font-size:<数字>px`、`font-size:<数字>pt`、数字 `QFont` size、`setPointSize(<数字>)` 或 `setPixelSize(<数字>)`。命名角色由 dataclass 字段唯一约束。

## 4. Canvas 时间轴几何

新增共享几何方法：

```python
TIME_LABEL = "00:00"
TIME_GAP = 8.0
TIME_LEFT_PADDING = 4.0

def _timeline_gutter(self, painter_or_metrics) -> float: ...
def _timeline_geometry(self, metrics, width) -> dict: ...
```

`gutter = left_padding + horizontalAdvance(TIME_LABEL) + TIME_GAP`。时间标签 QRect 的右缘为 `gutter - TIME_GAP`；网格起点和事件 column base 不小于 gutter。周视图七列宽从 `width - gutter` 计算；日视图可用宽从 `width - gutter - right_padding` 计算。周 header spacer 使用 Canvas 同一计算结果，可通过公开只读 helper/类方法取得，不能复制 30px。

时间标签使用 QRect + `AlignRight|AlignVCenter`；首末边界裁剪到 timeline。block 文本阈值改为 `fontMetrics.lineSpacing()` 加 padding。命中区域继续基于最终事项 rect。

测试暴露/记录 label rect、grid start、event rect，断言 `label.right + 8 <= min(grid_start, event.left)`。

## 5. 时态分类纯逻辑

在 `floating_window_logic.py` 新增：

```python
class FloatingEventState(str, Enum):
    NORMAL = "normal"
    CURRENT = "current"
    NEXT = "next"

def classify_event_states(events, now: datetime) -> dict[int, FloatingEventState]: ...
```

规则：

- 非法 ID、类型或时间跳过并记录为 normal/不映射，不抛到 UI。
- timespan current：`start <= now < end`。
- reminder duration>0 current：`start <= now < start+duration`。
- reminder duration=0 current：`start` 与 `now` 的年月日时分相同。
- current 可多项；future candidates 的 start 严格晚于 now，取最早 start 的所有并列为 next。
- current 优先于 next。

UI 把状态写入自定义 Qt role；主题样式或 item brush 使用 current/next 专用颜色，不能依赖 selected 状态。

## 6. 无边框、拖动与缩放

窗口 flags：

```python
Qt.Tool | Qt.FramelessWindowHint | Qt.WindowStaysOnBottomHint
```

`_set_pinned(bool)` 保证 top/bottom 互斥。切 flags 前缓存 geometry/visible，切换后恢复 geometry、opacity、show/raise（仅 pin 时 raise），更新按钮 checked/tooltip。状态仅成员变量。

拖动：在 window 和 event list viewport 安装 event filter。左键 press 记录 global position 和 frame top-left；移动超过 `QApplication.startDragDistance()` 后移动。输入框、pin 按钮、滚动条、resize zone 排除。release 清理状态。双击 item 信号只发 edit ID。

缩放：边缘宽度为独立几何常量；press 命中 left/right/top/bottom 组合，move 根据 global delta 调整 QRect，并受 minimumSize 与可见屏幕约束。resize 优先于 drag。cursor 随 edge 更新。

内部布局仅保留列表/空态与底部行（输入、短状态、pin）。无关闭按钮；托盘/设置继续控制可见性。

## 7. 事件类型转换与双击编辑

`EventService.update_event()` 白名单加入 `event_type`。合并 existing + fields 后按最终类型调用 `validate_event()`；规范化写入至少包含：

- `event_type` 在请求时写入；
- 转换为 reminder 时强制 `end_time=None`；
- 转换为 timespan 时校验非空且晚于 start；
- reminder 的 duration 保留输入，timespan 由 EventDialog 返回 0；
- 任何验证失败发生在 database.update_event 前。

`database.update_event()` 的字段白名单同步加入已有列 `event_type`，继续使用白名单字段名和参数化值构造单条 UPDATE；不新增或迁移 schema。

EventManager 现有编辑和 MainWindow 悬浮编辑都必须传 `event_type`，避免两个入口语义分叉。

`DailyFloatingWindow.event_edit_requested(int)` 由 itemDoubleClicked 发射。MainWindow 使用 Event.start_time 的日期打开 EventDialog；成功更新后只调用一次 `_on_data_changed()`。

## 8. 悬浮 AI 桥接

`AIChatWidget` 新增公共入口和状态信号：

```python
external_request_status = pyqtSignal(str)  # working/changed/unchanged/error:<message>/idle

def submit_external_message(self, text: str) -> bool: ...
```

主输入 `_send_message()` 与外部入口复用私有 `_submit_message(text, source)`：

- trim 后空文本拒绝；无配置拒绝并发出受限错误；
- `_ai_thread` 在运行时拒绝第二请求；
- 立即把 user 写入当前 Conversation，保存、重算 token、重绘主聊天；
- 设置 `_pending_conv_id` 和 `_pending_source` 后启动现有 AICallThread；
- result/error 仍按 `_pending_conv_id` 写回；完成后依据成功 CRUD 发 changed/unchanged/error，并恢复两个入口；
- `reply`/raw/think 正常写主对话，悬浮窗不消费正文。

DailyFloatingWindow：

- `ai_message_submitted(str)`；Enter 后不自行清空，只有 MainWindow/AIChatWidget 接受请求时清空并切 working。
- `set_ai_request_status(status)` 控制输入 enabled、短状态和错误摘要；不渲染正文。
- MainWindow 连接 submit 和 external status；`AIChatWidget.data_changed` 继续进入统一刷新。

隐藏悬浮窗不取消主对话请求；应用退出沿用主 worker 生命周期，不允许 `QThread destroyed while running`。

## 9. 错误处理

- 配置非法值回退默认，不自动回写。
- 设置保存失败保留对话框且不改变当前字号。
- 编辑验证/IO 错误由 UI 显示受限消息，不发刷新信号。
- 悬浮 AI 无配置、busy、timeout、连接、HTTP、畸形响应均显示短错误，同时主聊天按现有规则记录错误。
- 坏旧事件在悬浮列表/时态分类中跳过并写受限 ID 日志，不记录标题或描述。

## 10. 测试顺序

1. 先写 `test_typography.py`、设置与静态审计，旧实现因缺模块/字段/硬编码失败。
2. 写 Canvas gutter/8px 间距/8&20px 测试，旧固定坐标失败。
3. 写时态、frameless、bottom/top、拖缩、双击和 opacity 0 测试，旧实现失败。
4. 写 EventService 类型转换和两个编辑入口测试，旧白名单失败。
5. 写 external submit/current Conversation/并发/错误/隐藏输出测试，旧无公共入口失败。
6. 只在所有旧实现失败证据记录后分任务实施。
7. 每项聚焦通过后运行全量 89+；最终执行导入、offscreen、视觉、build check、完整构建和 exe 冒烟。

## 11. 回滚与兼容

- 新配置字段有默认值；旧版本忽略它们。回滚代码不会破坏 JSON。
- pin 不持久化，无迁移/清理。
- Event schema 不变；event_type 本来就是已有字段，转换只扩展服务白名单。
- 回滚源码和 docs 不触碰 `data/`、`dist/data/`；构建只原子替换 exe。

## 12. 开放问题

- 无阻塞性开放问题。真实 Windows DWM 的底层、无边框缩放和 0% 行为留作发布验证风险。
