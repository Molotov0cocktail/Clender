"""Source Manifest, component exposure, cleartext, and backup policy."""

from __future__ import annotations

from pathlib import PurePosixPath

from _support import A, PolicyTestCase


class ManifestSecurityTests(PolicyTestCase):
    MANIFEST = "app/src/main/AndroidManifest.xml"

    def _manifest(self):
        root = self.read_xml(self.MANIFEST)
        self.assertEqual("manifest", root.tag, "AndroidManifest root must be <manifest>")
        application = root.find("application")
        self.assertIsNotNone(application, "AndroidManifest must contain <application>")
        return root, application

    def test_cleartext_and_backup_are_explicitly_disabled(self) -> None:
        _root, application = self._manifest()
        assert application is not None
        self.assertEqual("false", application.get(A + "usesCleartextTraffic"), "cleartext traffic must be explicitly disabled")
        self.assertEqual("false", application.get(A + "allowBackup"), "application backup must be explicitly disabled")
        self.assertEqual("@xml/backup_rules", application.get(A + "fullBackupContent"), "pre-Android 12 backup rules must be explicit")
        self.assertEqual("@xml/data_extraction_rules", application.get(A + "dataExtractionRules"), "Android 12+ extraction rules must be explicit")

        network_config = application.get(A + "networkSecurityConfig")
        if network_config:
            self.assertTrue(network_config.startswith("@xml/"), "networkSecurityConfig must reference an XML resource")
            resource = PurePosixPath(network_config.removeprefix("@xml/")).name
            config = self.read_xml(f"app/src/main/res/xml/{resource}.xml")
            base = config.find("base-config")
            self.assertIsNotNone(base, "network security config requires base-config")
            self.assertEqual("false", base.get("cleartextTrafficPermitted") if base is not None else None, "base cleartext must be false")
            for domain in config.findall(".//domain-config"):
                self.assertNotEqual("true", domain.get("cleartextTrafficPermitted"), "domain cleartext overrides are forbidden")
            anchors = config.findall("./base-config/trust-anchors/certificates")
            self.assertEqual(["system"], [item.get("src") for item in anchors], "only system trust anchors are allowed")
            self.assertEqual([], config.findall("debug-overrides"), "debug/user CA overrides are forbidden")
            self.assertEqual([], config.findall(".//certificates[@src='user']"), "user-installed CAs are forbidden")

    def test_backup_rule_files_exclude_all_private_state(self) -> None:
        backup = self.read_xml("app/src/main/res/xml/backup_rules.xml")
        self.assertEqual("full-backup-content", backup.tag, "backup_rules root is invalid")
        exclusions = {(node.get("domain"), node.get("path")) for node in backup.findall("exclude")}
        required = {
            ("database", "."),
            ("sharedpref", "."),
            ("file", "datastore/"),
        }
        self.assertTrue(required <= exclusions, f"backup exclusions missing: {sorted(required - exclusions)}")
        self.assertEqual([], backup.findall("include"), "broad backup includes are forbidden")

        extraction = self.read_xml("app/src/main/res/xml/data_extraction_rules.xml")
        self.assertEqual("data-extraction-rules", extraction.tag, "data extraction root is invalid")
        for section_name in ("cloud-backup", "device-transfer"):
            section = extraction.find(section_name)
            self.assertIsNotNone(section, f"{section_name} rules are required")
            section_exclusions = {
                (node.get("domain"), node.get("path"))
                for node in section.findall("exclude")
            } if section is not None else set()
            self.assertTrue(required <= section_exclusions, f"{section_name} exclusions missing: {sorted(required - section_exclusions)}")
            self.assertEqual([], section.findall("include") if section is not None else [], f"{section_name} broad includes are forbidden")

    def test_components_have_minimum_explicit_exposure(self) -> None:
        _root, application = self._manifest()
        assert application is not None
        components = []
        for tag in ("activity", "activity-alias", "service", "receiver", "provider"):
            components.extend((tag, node) for node in application.findall(tag))
        self.assertTrue(components, "at least MainActivity must be declared")

        exported_true = []
        launcher_count = 0
        for tag, node in components:
            name = node.get(A + "name", "<unnamed>")
            exported = node.get(A + "exported")
            self.assertIn(exported, {"true", "false"}, f"{tag} {name} must explicitly declare android:exported")
            filters = node.findall("intent-filter")
            if filters:
                self.assertIsNotNone(exported, f"filtered {tag} {name} requires android:exported")
            if exported == "true":
                exported_true.append(name)
            for intent_filter in filters:
                actions = {item.get(A + "name") for item in intent_filter.findall("action")}
                categories = {item.get(A + "name") for item in intent_filter.findall("category")}
                self.assertNotIn("android.intent.category.BROWSABLE", categories, "BROWSABLE deep links are forbidden")
                self.assertEqual([], intent_filter.findall("data"), "implicit data deep links are forbidden")
                if actions == {"android.intent.action.MAIN"} and "android.intent.category.LAUNCHER" in categories:
                    launcher_count += 1
                    self.assertTrue(name.endswith(".MainActivity") or name == "MainActivity", "only MainActivity may be launcher-exported")
                    self.assertEqual("true", exported, "launcher MainActivity must be exported")
            if tag == "provider":
                self.assertEqual("false", exported, "providers must not be exported")
                self.assertEqual("false", node.get(A + "grantUriPermissions", "false"), "providers must not grant URI permissions")

        self.assertEqual(1, launcher_count, "exactly one MainActivity launcher is required")
        self.assertEqual(
            {
                ".app.MainActivity",
                ".widget.WidgetConfigurationActivity",
                ".widget.ClenderWidgetProvider",
            },
            set(exported_true),
            "only launcher, Widget configuration Activity, and Widget receiver may be exported",
        )
        self.assertEqual(
            {
                ("activity", ".app.MainActivity"),
                ("activity", ".widget.WidgetConfigurationActivity"),
                ("activity", ".widget.QuickAiActivity"),
                ("receiver", ".widget.ClenderWidgetProvider"),
                ("receiver", ".widget.WidgetLocalRefreshReceiver"),
                ("receiver", ".widget.WidgetBootReceiver"),
                ("receiver", ".alert.EventAlertReceiver"),
                ("receiver", ".alert.AlertRestoreReceiver"),
                ("service", ".alert.ImportantAlarmService"),
                ("service", "androidx.room.MultiInstanceInvalidationService"),
            },
            {(tag, node.get(A + "name")) for tag, node in components},
            "source manifest component delta must remain exact",
        )
        alarm_service = next(node for tag, node in components
                             if tag == "service" and node.get(A + "name") == ".alert.ImportantAlarmService")
        self.assertEqual(
            {A + "name": ".alert.ImportantAlarmService", A + "enabled": "true",
             A + "exported": "false", A + "foregroundServiceType": "mediaPlayback"},
            alarm_service.attrib,
        )
        self.assertEqual([], list(alarm_service))
        configuration = next(
            node
            for tag, node in components
            if tag == "activity" and node.get(A + "name") == ".widget.WidgetConfigurationActivity"
        )
        self.assertEqual(
            {
                "name": ".widget.WidgetConfigurationActivity",
                "enabled": "true",
                "exported": "true",
                "excludeFromRecents": "true",
                "theme": "@style/Theme.Clender",
            },
            {
                key.removeprefix(A): value
                for key, value in configuration.attrib.items()
                if key.startswith(A)
            },
        )
        self.assertEqual(
            ["android.appwidget.action.APPWIDGET_CONFIGURE"],
            [
                action.get(A + "name")
                for intent_filter in configuration.findall("intent-filter")
                for action in intent_filter.findall("action")
            ],
        )
        self.assertEqual([], configuration.findall(".//category"))
        self.assertEqual([], configuration.findall(".//data"))
        widget = next(
            node
            for tag, node in components
            if tag == "receiver" and node.get(A + "name") == ".widget.ClenderWidgetProvider"
        )
        self.assertEqual("true", widget.get(A + "enabled"))
        self.assertNotIn(A + "directBootAware", widget.attrib)
        self.assertEqual(
            ["android.appwidget.action.APPWIDGET_UPDATE"],
            [
                action.get(A + "name")
                for intent_filter in widget.findall("intent-filter")
                for action in intent_filter.findall("action")
            ],
        )
        self.assertEqual(
            {"android.appwidget.provider": "@xml/clender_widget_info"},
            {
                item.get(A + "name"): item.get(A + "resource")
                for item in widget.findall("meta-data")
            },
        )
        refresh = next(
            node
            for tag, node in components
            if tag == "receiver" and node.get(A + "name") == ".widget.WidgetLocalRefreshReceiver"
        )
        self.assertEqual(
            {
                "name": ".widget.WidgetLocalRefreshReceiver",
                "enabled": "true",
                "exported": "false",
            },
            {
                key.removeprefix(A): value
                for key, value in refresh.attrib.items()
                if key.startswith(A)
            },
        )
        self.assertEqual([], refresh.findall("intent-filter"))
        self.assertEqual([], refresh.findall("meta-data"))
        quick_ai = next(
            node for tag, node in components
            if tag == "activity" and node.get(A + "name") == ".widget.QuickAiActivity"
        )
        self.assertEqual(
            {
                "name": ".widget.QuickAiActivity",
                "enabled": "true",
                "exported": "false",
                "excludeFromRecents": "true",
                "theme": "@style/Theme.Clender",
            },
            {key.removeprefix(A): value for key, value in quick_ai.attrib.items()},
        )
        self.assertEqual([], list(quick_ai))
        boot = next(
            node for tag, node in components
            if tag == "receiver" and node.get(A + "name") == ".widget.WidgetBootReceiver"
        )
        self.assertEqual(
            {"name": ".widget.WidgetBootReceiver", "enabled": "true", "exported": "false"},
            {key.removeprefix(A): value for key, value in boot.attrib.items()},
        )
        self.assertEqual(1, len(boot.findall("intent-filter")))
        self.assertEqual(
            ["android.intent.action.BOOT_COMPLETED"],
            [node.get(A + "name") for node in boot.findall(".//action")],
        )
        self.assertEqual([], boot.findall(".//category"))
        self.assertEqual([], boot.findall(".//data"))
        self.assertEqual([], boot.findall("meta-data"))
        for name in ("EventAlertReceiver", "AlertRestoreReceiver"):
            receiver = next(node for tag, node in components
                            if tag == "receiver" and node.get(A + "name") == f".alert.{name}")
            self.assertEqual(
                {"name": f".alert.{name}", "enabled": "true", "exported": "false"},
                {key.removeprefix(A): value for key, value in receiver.attrib.items()},
            )
            if name == "EventAlertReceiver":
                self.assertEqual([], list(receiver))
            else:
                self.assertEqual(["intent-filter"], [node.tag for node in receiver])
                intent_filter = receiver.find("intent-filter")
                self.assertEqual({}, intent_filter.attrib)
                self.assertEqual(["action"] * 4, [node.tag for node in intent_filter])
                self.assertEqual(
                    sorted((
                        "android.intent.action.BOOT_COMPLETED",
                        "android.intent.action.TIME_SET",
                        "android.intent.action.TIMEZONE_CHANGED",
                        "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
                    )),
                    sorted(node.get(A + "name") for node in intent_filter),
                )
                for action in intent_filter:
                    self.assertEqual({A + "name"}, set(action.attrib))
                    self.assertEqual([], list(action))

    def test_manifest_has_no_debug_test_flags_or_sensitive_permissions(self) -> None:
        root, application = self._manifest()
        assert application is not None
        self.assertNotEqual("true", application.get(A + "debuggable"), "main manifest must not enable debuggable")
        self.assertNotEqual("true", application.get(A + "testOnly"), "main manifest must not enable testOnly")
        allowed = {
            "android.permission.INTERNET", "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.POST_NOTIFICATIONS", "android.permission.SCHEDULE_EXACT_ALARM",
            "android.permission.WAKE_LOCK",
            "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
        }
        permissions = {
            node.get(A + "name")
            for node in root.findall("uses-permission")
        }
        self.assertTrue(permissions <= allowed, f"unexpected Android permissions: {sorted(item for item in permissions - allowed if item)}")
        provider_source = self.read_text(
            "app/src/main/java/com/molotov/clender/widget/ClenderWidgetProvider.kt"
        )
        for forbidden in (
            "override fun onReceive",
            "PendingIntent",
            "setOnClickPendingIntent",
            "fillInIntent",
            "RemoteViewsService",
            "runBlocking",
            "Thread.sleep",
        ):
            self.assertNotIn(forbidden, provider_source)

        self._assert_widget_pending_intent_surface()
        self._assert_widget_provider_info_delta()

    def _assert_widget_pending_intent_surface(self) -> None:
        kotlin_root = self.path("app/src/main/java/com/molotov/clender")
        platform_factories = []
        for source in kotlin_root.rglob("*.kt"):
            text = source.read_text(encoding="utf-8")
            for platform_call in (
                "PendingIntent.getActivity(",
                "PendingIntent.getBroadcast(",
                "PendingIntent.getService(",
                "PendingIntent.getForegroundService(",
            ):
                if platform_call in text:
                    platform_factories.extend(
                        [(source.relative_to(self.path("")).as_posix(), platform_call)] * text.count(platform_call)
                    )
            if "PendingIntent.get" in text:
                self.assertIn("PendingIntent.FLAG_IMMUTABLE", text)
            for forbidden in ("FLAG_MUTABLE", "FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT"):
                self.assertNotIn(forbidden, text)
            for platform_type in ("AlarmManager", "NotificationManager"):
                if platform_type in text:
                    self.assertEqual(
                        "app/src/main/java/com/molotov/clender/alert/PlatformEventAlerts.kt",
                        source.relative_to(self.path("")).as_posix(),
                        "alert platform calls must remain in the exact local adapter",
                    )
        self.assertEqual(
            sorted([
                (
                    "app/src/main/java/com/molotov/clender/widget/WidgetPendingIntentFactory.kt",
                    "PendingIntent.getActivity(",
                ),
                (
                    "app/src/main/java/com/molotov/clender/widget/WidgetPendingIntentFactory.kt",
                    "PendingIntent.getBroadcast(",
                ),
                (
                    "app/src/main/java/com/molotov/clender/alert/AlertPendingIntents.kt",
                    "PendingIntent.getActivity(",
                ),
                (
                    "app/src/main/java/com/molotov/clender/alert/AlertPendingIntents.kt",
                    "PendingIntent.getBroadcast(",
                ),
            ]),
            sorted(platform_factories),
            "only the exact Widget and alert immutable factories may create platform tokens",
        )
        renderer = self.read_text(
            "app/src/main/java/com/molotov/clender/widget/WidgetRemoteViewsRenderer.kt"
        )
        self.assertEqual(4, renderer.count("setOnClickPendingIntent"))
        self.assertIn("R.id.widget_configure", renderer)
        self.assertIn("R.id.widget_event_row", renderer)
        self.assertIn("R.id.widget_refresh", renderer)
        production_text = "\n".join(
            source.read_text(encoding="utf-8") for source in kotlin_root.rglob("*.kt")
        )
        for forbidden in (
            "class WidgetRefreshWorker",
            "PeriodicWorkRequest", "setExpedited(", "setForeground(",
            "setForegroundAsync(",
        ):
            self.assertNotIn(forbidden, production_text)

    def _assert_widget_provider_info_delta(self) -> None:
        provider_info = self.read_xml("app/src/main/res/xml/clender_widget_info.xml")
        self.assertEqual("appwidget-provider", provider_info.tag)
        self.assertEqual(
            "com.molotov.clender.widget.WidgetConfigurationActivity",
            provider_info.get(A + "configure"),
        )
        self.assertEqual(
            "reconfigurable|configuration_optional",
            provider_info.get(A + "widgetFeatures"),
        )
        self.assertEqual("@layout/widget_clender_small", provider_info.get(A + "initialLayout"))
        self.assertEqual("@layout/widget_clender_medium", provider_info.get(A + "previewLayout"))
        self.assertEqual("0", provider_info.get(A + "updatePeriodMillis"))
        self.assertEqual("home_screen", provider_info.get(A + "widgetCategory"))


if __name__ == "__main__":
    import unittest

    unittest.main()
