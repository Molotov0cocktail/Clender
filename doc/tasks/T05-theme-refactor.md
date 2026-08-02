# T05 — 重构主题系统

## 目标

重构 `theme_manager.py`：引入 `ThemeColors` 类型化包装器，将 `import theme_manager` 改为从 `constants.py` 导入默认配置。

## 输入文档

- `doc/detailed-design.md` §2.4
- `T02-constants-logger.md` — DEFAULT_CONFIG 已在 constants 中定义

## 预计变更文件

| 操作 | 文件 | 说明 |
|------|------|------|
| ✏️ 修改 | `theme_manager.py` | 引入 ThemeColors 类，保持 LIGHT/DARK_THEME 字典不变 |
| ✏️ 修改 | `config.py` | DEFAULT_CONFIG 从 constants 导入 |

## 实现步骤

### Step 1：在 theme_manager.py 中添加 ThemeColors

```python
class ThemeColors:
    """主题颜色包装器 - 提供类型安全访问"""
    def __init__(self, theme_dict: dict):
        self._d = theme_dict
    
    def __getattr__(self, name: str) -> str:
        if name.startswith('_'):
            raise AttributeError(name)
        val = self._d.get(name)
        if val is None:
            raise AttributeError(f"Theme key '{name}' not found")
        return val
    
    def get(self, key: str, default: str = "") -> str:
        return self._d.get(key, default)
```

### Step 2：修改 get_current_theme() 返回 ThemeColors

```python
def get_current_theme() -> ThemeColors:
    theme_name = cfg_mod.get_theme()
    return ThemeColors(DARK_THEME if theme_name == 'dark' else LIGHT_THEME)
```

### Step 3：修改 config.py 导入 DEFAULT_CONFIG

从 `constants.py` 导入 `DEFAULT_CONFIG`，移除 config.py 中的重复定义。

### Step 4：向后兼容

`ThemeColors` 支持 `t["primary"]` 风格的 dict 访问，通过实现 `__getitem__` 方法：

```python
def __getitem__(self, key: str) -> str:
    return self._d[key]
```

### Step 5：验证

- [ ] `get_current_theme()` 返回 ThemeColors 对象
- [ ] `t.primary` 和 `t["primary"]` 均正常工作
- [ ] 日间/夜间主题切换正常
- [ ] 所有 Widget 的 apply_theme() 正常工作

## 测试与检查

```bash
python -c "import theme_manager; t = theme_manager.get_current_theme(); print(t.primary, t['primary'])"
```

## 完成定义

- [x] ThemeColors 类添加完成
- [x] 向后完全兼容 dict 访问方式
- [x] config.py 不再重复定义 DEFAULT_CONFIG
- [x] 主题切换功能不变

## 依赖

- T02（constants.py）