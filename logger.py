"""统一日志系统；文件输出由应用启动流程显式配置。"""
import logging
import os


_HANDLER_MARKER = "_clender_handler"


def shutdown_logging() -> None:
    """Close and remove handlers installed by :func:`configure_logging`."""
    root = logging.getLogger()
    for handler in list(root.handlers):
        if getattr(handler, _HANDLER_MARKER, False):
            root.removeHandler(handler)
            handler.close()


def configure_logging(data_dir: str) -> None:
    """Configure root logging explicitly; safe to call more than once."""
    os.makedirs(data_dir, exist_ok=True)
    root = logging.getLogger()
    root.setLevel(logging.DEBUG)

    shutdown_logging()

    file_handler = logging.FileHandler(
        os.path.join(data_dir, "clender.log"), encoding="utf-8"
    )
    file_handler.setLevel(logging.DEBUG)
    file_handler.setFormatter(logging.Formatter(
        '[%(asctime)s] %(name)s %(levelname)s: %(message)s',
        datefmt='%Y-%m-%d %H:%M:%S',
    ))
    setattr(file_handler, _HANDLER_MARKER, True)
    root.addHandler(file_handler)

    console_handler = logging.StreamHandler()
    console_handler.setLevel(logging.WARNING)
    console_handler.setFormatter(logging.Formatter('%(levelname)s: %(message)s'))
    setattr(console_handler, _HANDLER_MARKER, True)
    root.addHandler(console_handler)


def get_logger(name: str) -> logging.Logger:
    """Return a named logger without causing filesystem side effects."""
    return logging.getLogger(name)
