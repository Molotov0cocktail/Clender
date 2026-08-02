import tempfile
import unittest
from pathlib import Path
from unittest import mock

import build


class BuildTests(unittest.TestCase):
    def test_build_command_excludes_runtime_data(self):
        with tempfile.TemporaryDirectory() as temp:
            project = Path(temp)
            (project / "main.py").write_text("print('ok')", encoding="utf-8")
            (project / "requirements.txt").write_text("", encoding="utf-8")
            (project / "ui").mkdir()

            command = build.build_command(project)

        joined = " ".join(str(part) for part in command)
        self.assertNotIn("--add-data", command)
        self.assertIn("main.py", joined)
        self.assertIn("PyInstaller", joined)
        self.assertIn(str(project / "build" / "release"), joined)
        self.assertNotIn(str(project / "dist" / "data"), joined)

    def test_environment_requires_python_312(self):
        result = build.check_environment()
        self.assertTrue(result.ok, result.message)
        self.assertEqual(result.python_version[:2], (3, 12))

    def test_build_subprocess_disables_inaccessible_user_site(self):
        with tempfile.TemporaryDirectory() as temp:
            environment = build.build_environment(Path(temp))
        self.assertEqual(environment["PYTHONNOUSERSITE"], "1")
        self.assertIn(str(Path("build") / "userbase"), environment["PYTHONUSERBASE"])
        self.assertIn(str(Path(build.sys.prefix) / "Library" / "bin"), environment["PATH"])

    def test_build_publishes_only_exe_and_preserves_dist_data(self):
        with tempfile.TemporaryDirectory() as temp:
            project = Path(temp)
            (project / "main.py").write_text("print('ok')", encoding="utf-8")
            user_data = project / "dist" / "data" / "config.json"
            user_data.parent.mkdir(parents=True)
            user_data.write_text("user-owned", encoding="utf-8")

            def fake_run(*args, **kwargs):
                staged = project / "build" / "release" / "Clender.exe"
                staged.parent.mkdir(parents=True, exist_ok=True)
                staged.write_bytes(b"new-exe")

            check = build.EnvironmentCheck(True, "ok", (3, 12, 4), "python")
            with mock.patch.object(build, "check_environment", return_value=check), mock.patch.object(
                build.subprocess, "run", side_effect=fake_run
            ):
                result = build.build_exe(project)

            self.assertEqual(result.read_bytes(), b"new-exe")
            self.assertEqual(user_data.read_text(encoding="utf-8"), "user-owned")


if __name__ == "__main__":
    unittest.main()
