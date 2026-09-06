"""Audit the generated debug and release manifests after Gradle merging."""

from __future__ import annotations

from _support import A, PolicyTestCase


class MergedManifestSecurityTests(PolicyTestCase):
    VARIANTS = {
        "debug": "app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml",
        "release": "app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml",
    }
    WORK_PERMISSIONS = {
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.RECEIVE_BOOT_COMPLETED",
        "android.permission.WAKE_LOCK",
    }
    STARTUP_METADATA = {
        "androidx.emoji2.text.EmojiCompatInitializer": "androidx.startup",
        "androidx.lifecycle.ProcessLifecycleInitializer": "androidx.startup",
        "androidx.profileinstaller.ProfileInstallerInitializer": "androidx.startup",
        "androidx.work.WorkManagerInitializer": "androidx.startup",
        "okhttp3.internal.platform.PlatformInitializer": "androidx.startup",
    }

    def _manifest(self, variant: str):
        root = self.read_xml(self.VARIANTS[variant])
        application = root.find("application")
        self.assertIsNotNone(application, f"{variant} merged manifest requires an application")
        return root, application

    @staticmethod
    def _android_attributes(node) -> dict[str, str]:
        return {
            key.removeprefix(A): value
            for key, value in node.attrib.items()
            if key.startswith(A)
        }

    @staticmethod
    def _filters(node) -> list[tuple[tuple[str | None, ...], tuple[str | None, ...]]]:
        result = []
        for intent_filter in node.findall("intent-filter"):
            actions = tuple(sorted(item.get(A + "name") for item in intent_filter.findall("action")))
            categories = tuple(sorted(item.get(A + "name") for item in intent_filter.findall("category")))
            result.append((actions, categories))
        return sorted(result)

    def _components(self, application) -> dict[tuple[str, str | None], object]:
        result = {}
        for tag in ("activity", "activity-alias", "service", "receiver", "provider"):
            for node in application.findall(tag):
                key = (tag, node.get(A + "name"))
                self.assertNotIn(key, result, f"duplicate merged component: {key}")
                result[key] = node
        return result

    def test_merged_manifests_keep_network_and_backup_hardening(self) -> None:
        for variant in self.VARIANTS:
            with self.subTest(variant=variant):
                _root, application = self._manifest(variant)
                assert application is not None
                self.assertEqual("false", application.get(A + "usesCleartextTraffic"))
                self.assertEqual("false", application.get(A + "allowBackup"))
                self.assertEqual("@xml/backup_rules", application.get(A + "fullBackupContent"))
                self.assertEqual("@xml/data_extraction_rules", application.get(A + "dataExtractionRules"))
                if variant == "release":
                    self.assertNotEqual("true", application.get(A + "debuggable"))

    def test_merged_permissions_are_an_exact_allowlist(self) -> None:
        for variant in self.VARIANTS:
            with self.subTest(variant=variant):
                root, _application = self._manifest(variant)
                package_name = root.get("package")
                permissions = {
                    item.get(A + "name")
                    for item in root.findall("uses-permission")
                }
                self.assertEqual(
                    {
                        "android.permission.INTERNET",
                        f"{package_name}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
                        *self.WORK_PERMISSIONS,
                    },
                    permissions,
                )
                self.assertEqual([], root.findall("instrumentation"), "test runner must not enter app manifests")

    def test_merged_components_are_an_exact_hardened_allowlist(self) -> None:
        for variant in self.VARIANTS:
            with self.subTest(variant=variant):
                root, application = self._manifest(variant)
                assert application is not None
                package_name = root.get("package")
                expected = {
                    ("activity", "com.molotov.clender.app.MainActivity"): {
                        "name": "com.molotov.clender.app.MainActivity",
                        "exported": "true",
                    },
                    ("activity", "com.molotov.clender.widget.WidgetConfigurationActivity"): {
                        "name": "com.molotov.clender.widget.WidgetConfigurationActivity",
                        "enabled": "true",
                        "exported": "true",
                        "excludeFromRecents": "true",
                        "theme": "@style/Theme.Clender",
                    },
                    ("provider", "androidx.startup.InitializationProvider"): {
                        "name": "androidx.startup.InitializationProvider",
                        "authorities": f"{package_name}.androidx-startup",
                        "exported": "false",
                    },
                    ("activity", "com.molotov.clender.widget.QuickAiActivity"): {
                        "name": "com.molotov.clender.widget.QuickAiActivity",
                        "enabled": "true",
                        "exported": "false",
                        "excludeFromRecents": "true",
                        "theme": "@style/Theme.Clender",
                    },
                    ("receiver", "com.molotov.clender.widget.WidgetBootReceiver"): {
                        "name": "com.molotov.clender.widget.WidgetBootReceiver",
                        "enabled": "true",
                        "exported": "false",
                    },
                    ("service", "androidx.work.impl.background.systemjob.SystemJobService"): {
                        "name": "androidx.work.impl.background.systemjob.SystemJobService",
                        "directBootAware": "false",
                        "enabled": "@bool/enable_system_job_service_default",
                        "exported": "true",
                        "permission": "android.permission.BIND_JOB_SERVICE",
                    },
                    ("service", "androidx.work.impl.foreground.SystemForegroundService"): {
                        "name": "androidx.work.impl.foreground.SystemForegroundService",
                        "directBootAware": "false",
                        "enabled": "@bool/enable_system_foreground_service_default",
                        "exported": "false",
                    },
                    ("receiver", "androidx.work.impl.utils.ForceStopRunnable$BroadcastReceiver"): {
                        "name": "androidx.work.impl.utils.ForceStopRunnable$BroadcastReceiver",
                        "directBootAware": "false",
                        "enabled": "true",
                        "exported": "false",
                    },
                    ("receiver", "androidx.work.impl.background.systemalarm.RescheduleReceiver"): {
                        "name": "androidx.work.impl.background.systemalarm.RescheduleReceiver",
                        "directBootAware": "false",
                        "enabled": "false",
                        "exported": "false",
                    },
                    ("receiver", "androidx.work.impl.diagnostics.DiagnosticsReceiver"): {
                        "name": "androidx.work.impl.diagnostics.DiagnosticsReceiver",
                        "directBootAware": "false",
                        "enabled": "true",
                        "exported": "true",
                        "permission": "android.permission.DUMP",
                    },
                    ("receiver", "androidx.profileinstaller.ProfileInstallReceiver"): {
                        "name": "androidx.profileinstaller.ProfileInstallReceiver",
                        "directBootAware": "false",
                        "enabled": "true",
                        "exported": "true",
                        "permission": "android.permission.DUMP",
                    },
                    ("receiver", "com.molotov.clender.widget.ClenderWidgetProvider"): {
                        "name": "com.molotov.clender.widget.ClenderWidgetProvider",
                        "enabled": "true",
                        "exported": "true",
                    },
                    ("receiver", "com.molotov.clender.widget.WidgetLocalRefreshReceiver"): {
                        "name": "com.molotov.clender.widget.WidgetLocalRefreshReceiver",
                        "enabled": "true",
                        "exported": "false",
                    },
                }
                components = self._components(application)
                actual = {key: self._android_attributes(node) for key, node in components.items()}
                self.assertEqual(expected, actual)

                provider = components[("provider", "androidx.startup.InitializationProvider")]
                metadata = {
                    item.get(A + "name"): item.get(A + "value")
                    for item in provider.findall("meta-data")
                }
                self.assertEqual(self.STARTUP_METADATA, metadata)
                widget_receiver = components[("receiver", "com.molotov.clender.widget.ClenderWidgetProvider")]
                widget_metadata = {
                    item.get(A + "name"): item.get(A + "resource")
                    for item in widget_receiver.findall("meta-data")
                }
                self.assertEqual(
                    {"android.appwidget.provider": "@xml/clender_widget_info"},
                    widget_metadata,
                )
                self.assertEqual([], application.findall("meta-data"), "application-level metadata is forbidden")

                expected_filters = {
                    ("activity", "com.molotov.clender.app.MainActivity"): [
                        (("android.intent.action.MAIN",), ("android.intent.category.LAUNCHER",)),
                    ],
                    ("activity", "com.molotov.clender.widget.WidgetConfigurationActivity"): [
                        (("android.appwidget.action.APPWIDGET_CONFIGURE",), ()),
                    ],
                    ("receiver", "androidx.work.impl.background.systemalarm.RescheduleReceiver"): [
                        (("android.intent.action.BOOT_COMPLETED",), ()),
                    ],
                    ("receiver", "androidx.work.impl.diagnostics.DiagnosticsReceiver"): [
                        (("androidx.work.diagnostics.REQUEST_DIAGNOSTICS",), ()),
                    ],
                    ("receiver", "androidx.profileinstaller.ProfileInstallReceiver"): [
                        (("androidx.profileinstaller.action.BENCHMARK_OPERATION",), ()),
                        (("androidx.profileinstaller.action.INSTALL_PROFILE",), ()),
                        (("androidx.profileinstaller.action.SAVE_PROFILE",), ()),
                        (("androidx.profileinstaller.action.SKIP_FILE",), ()),
                    ],
                    ("receiver", "com.molotov.clender.widget.ClenderWidgetProvider"): [
                        (("android.appwidget.action.APPWIDGET_UPDATE",), ()),
                    ],
                    ("receiver", "com.molotov.clender.widget.WidgetBootReceiver"): [
                        (("android.intent.action.BOOT_COMPLETED",), ()),
                    ],
                }
                actual_filters = {
                    key: filters
                    for key, node in components.items()
                    if (filters := self._filters(node))
                }
                self.assertEqual(expected_filters, actual_filters)

                self.assertNotEqual("true", application.get(A + "testOnly"))
                for (tag, name), node in components.items():
                    lowered = (name or "").lower()
                    self.assertNotIn("androidx.glance", lowered)
                    if "appwidget" in lowered:
                        self.assertIn(
                            (tag, name),
                            {
                                ("receiver", "com.molotov.clender.widget.ClenderWidgetProvider"),
                            },
                            "no dependency-owned appwidget component is allowed",
                        )
                    if tag == "service":
                        self.assertFalse(
                            lowered.startswith("com.molotov.clender."),
                            "application-owned services are forbidden",
                        )
                    if tag == "provider":
                        self.assertFalse(lowered.startswith("androidx.test.") or "testprovider" in lowered)
                    for intent_filter in node.findall("intent-filter"):
                        categories = {item.get(A + "name") for item in intent_filter.findall("category")}
                        self.assertNotIn("android.intent.category.BROWSABLE", categories)
                        self.assertEqual([], intent_filter.findall("data"))

        network = self.read_xml("app/src/main/res/xml/network_security_config.xml")
        self.assertEqual([], network.findall("debug-overrides"), "debug CA overrides are forbidden")
        self.assertEqual([], network.findall(".//certificates[@src='user']"), "user CAs are forbidden")


if __name__ == "__main__":
    import unittest

    unittest.main()
