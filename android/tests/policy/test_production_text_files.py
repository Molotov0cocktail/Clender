"""Synthetic roots only: local config exclusions must be proven by Git metadata."""

import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import _support
import test_ignore_and_boundaries as boundaries


class ProductionTextFilesTests(unittest.TestCase):
    def setUp(self):
        scratch = _support.ANDROID_ROOT / ".tmp"
        scratch.mkdir(exist_ok=True)
        temporary = tempfile.TemporaryDirectory(prefix="policy-text-fixture-", dir=scratch)
        self.addCleanup(temporary.cleanup)
        self.repo = Path(temporary.name)
        self.root = self.repo / "android"
        self.root.mkdir()
        for name, value in (("REPO_ROOT", self.repo), ("ANDROID_ROOT", self.root)):
            context = patch.object(_support, name, value)
            context.start()
            self.addCleanup(context.stop)
        self.policy = boundaries.IgnoreAndBoundaryTests()

    def synthetic(self, name):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("storePassword=SYNTHETIC-NOT-A-SECRET\n", encoding="utf-8")
        return path

    def git(self, *, tracked=False, ignored=True, failed=None):
        def run(args, **kwargs):
            self.assertIn(args[0], ("ls-files", "check-ignore"))
            self.assertIn("--", args)
            self.assertIn(args[-1], ("android/keystore.properties", "android/local.properties"))
            status = (0 if tracked else 1) if args[0] == "ls-files" else (0 if ignored else 1)
            if args[0] == failed:
                status = 128
            return subprocess.CompletedProcess(args, status, "", "")
        return patch.object(self.policy, "run_git", side_effect=run)

    def test_ignored_untracked_root_configs_excluded_without_reading(self):
        for name in ("keystore.properties", "local.properties"):
            self.synthetic(name)
        with self.git(), patch.object(Path, "read_text", side_effect=AssertionError("must not read local config")):
            self.assertEqual(self.policy.production_text_files(), [])
            self.policy.test_android_text_has_no_real_secrets_or_host_specific_paths()

    def test_tracked_configs_still_fail_even_when_ignore_rule_matches(self):
        for name in ("keystore.properties", "local.properties"):
            path = self.synthetic(name)
            with self.subTest(name=name), self.git(tracked=True):
                self.assertIn(path, self.policy.production_text_files())
                with self.assertRaises(AssertionError):
                    self.policy.test_android_text_has_no_real_secrets_or_host_specific_paths()

    def test_unignored_untracked_configs_still_fail(self):
        for name in ("keystore.properties", "local.properties"):
            path = self.synthetic(name)
            with self.subTest(name=name), self.git(ignored=False):
                self.assertIn(path, self.policy.production_text_files())
                with self.assertRaises(AssertionError):
                    self.policy.test_android_text_has_no_real_secrets_or_host_specific_paths()

    def test_git_failures_do_not_silently_exclude_configs(self):
        self.synthetic("keystore.properties")
        for command in ("ls-files", "check-ignore"):
            with self.subTest(command=command), self.git(failed=command):
                with self.assertRaises(AssertionError):
                    self.policy.production_text_files()

    def test_nested_and_other_properties_remain_scanned_even_if_ignored(self):
        paths = [self.synthetic(name) for name in (
            "app/keystore.properties", "app/local.properties", "other.properties", "gradle.properties"
        )]
        with patch.object(self.policy, "run_git", side_effect=AssertionError("no broad exclusion")):
            self.assertEqual(set(self.policy.production_text_files()), set(paths))
            with self.assertRaises(AssertionError):
                self.policy.test_android_text_has_no_real_secrets_or_host_specific_paths()
