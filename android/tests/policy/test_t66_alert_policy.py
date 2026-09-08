"""T66 alert additions remain exact; synthetic mutations must fail closed."""

import copy
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest.mock import patch

import test_manifest_security as source_policy
import test_merged_manifest_security as merged_policy
from _support import A, ANDROID_ROOT


PERMISSIONS = ("android.permission.POST_NOTIFICATIONS", "android.permission.SCHEDULE_EXACT_ALARM")
RECEIVERS = ("EventAlertReceiver", "AlertRestoreReceiver")
ACTIONS = (
    "android.intent.action.BOOT_COMPLETED",
    "android.intent.action.TIME_SET",
    "android.intent.action.TIMEZONE_CHANGED",
    "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
)


def alert_manifest(root, merged=False):
    root = copy.deepcopy(root)
    application = root.find("application")
    for name in PERMISSIONS:
        if not any(node.get(A + "name") == name for node in root.findall("uses-permission")):
            ET.SubElement(root, "uses-permission", {A + "name": name})
    prefix = "com.molotov.clender" if merged else ""
    for name in RECEIVERS:
        full_name = f"{prefix}.alert.{name}"
        existing = application.find(f"receiver[@{A}name='{full_name}']")
        if existing is not None:
            application.remove(existing)
        receiver = ET.SubElement(application, "receiver", {
            A + "name": full_name, A + "enabled": "true", A + "exported": "false",
        })
        if name == "AlertRestoreReceiver":
            intent_filter = ET.SubElement(receiver, "intent-filter")
            for action in ACTIONS:
                ET.SubElement(intent_filter, "action", {A + "name": action})
    return root, application


def foreground_alarm_manifest(root, merged=False):
    root, application = alert_manifest(root, merged)
    for name in ("FOREGROUND_SERVICE", "FOREGROUND_SERVICE_MEDIA_PLAYBACK"):
        full_name = "android.permission." + name
        if not any(node.get(A + "name") == full_name for node in root.findall("uses-permission")):
            ET.SubElement(root, "uses-permission", {A + "name": full_name})
    prefix = "com.molotov.clender" if merged else ""
    name = prefix + ".alert.ImportantAlarmService"
    existing = application.find(f"service[@{A}name='{name}']")
    if existing is not None:
        application.remove(existing)
    ET.SubElement(application, "service", {
        A + "name": name, A + "enabled": "true", A + "exported": "false",
        A + "foregroundServiceType": "mediaPlayback",
    })
    return root, application


class T66AlertPolicyTests(unittest.TestCase):
    def test_exact_alarm_foreground_service_permissions_are_accepted(self):
        case = source_policy.ManifestSecurityTests()
        self.assert_source_permissions(*foreground_alarm_manifest(case._manifest()[0]))

    def test_exact_alarm_foreground_service_component_is_accepted(self):
        case = source_policy.ManifestSecurityTests()
        self.assert_source_components(*foreground_alarm_manifest(case._manifest()[0]))

    def test_alarm_service_extra_exposure_type_and_filter_are_rejected(self):
        for mutation in ("exported", "process", "type", "filter", "extra"):
            with self.subTest(mutation=mutation):
                case = source_policy.ManifestSecurityTests()
                root, app = foreground_alarm_manifest(case._manifest()[0])
                service = app.find(f"service[@{A}name='.alert.ImportantAlarmService']")
                if mutation == "exported":
                    service.set(A + "exported", "true")
                elif mutation == "process":
                    service.set(A + "process", ":alarm")
                elif mutation == "type":
                    service.set(A + "foregroundServiceType", "mediaPlayback|dataSync")
                elif mutation == "filter":
                    ET.SubElement(service, "intent-filter")
                else:
                    ET.SubElement(app, "service", {A + "name": ".alert.OtherService", A + "exported": "false"})
                with self.assertRaises(AssertionError):
                    self.assert_source_components(root, app)

    def test_foreground_alarm_does_not_authorize_other_foreground_capabilities(self):
        for name in ("FOREGROUND_SERVICE_DATA_SYNC", "FOREGROUND_SERVICE_MICROPHONE", "ACCESS_NOTIFICATION_POLICY"):
            with self.subTest(permission=name):
                case = source_policy.ManifestSecurityTests()
                root, app = foreground_alarm_manifest(case._manifest()[0])
                ET.SubElement(root, "uses-permission", {A + "name": "android.permission." + name})
                with self.assertRaises(AssertionError):
                    self.assert_source_permissions(root, app)

    def source_fixture(self):
        case = source_policy.ManifestSecurityTests()
        return case, alert_manifest(case._manifest()[0])

    def assert_source_permissions(self, root, application):
        case = source_policy.ManifestSecurityTests()
        with patch.object(case, "_manifest", return_value=(root, application)), \
                patch.object(case, "_assert_widget_pending_intent_surface"):
            case.test_manifest_has_no_debug_test_flags_or_sensitive_permissions()

    def assert_source_components(self, root, application):
        case = source_policy.ManifestSecurityTests()
        with patch.object(case, "_manifest", return_value=(root, application)):
            case.test_components_have_minimum_explicit_exposure()

    def test_two_authorized_permissions_are_accepted(self):
        _case, fixture = self.source_fixture()
        self.assert_source_permissions(*fixture)

    def test_unrequested_permissions_are_rejected(self):
        for permission in ("USE_EXACT_ALARM", "USE_FULL_SCREEN_INTENT", "READ_CALENDAR"):
            with self.subTest(permission=permission):
                _case, (root, application) = self.source_fixture()
                ET.SubElement(root, "uses-permission", {A + "name": "android.permission." + permission})
                with self.assertRaises(AssertionError):
                    self.assert_source_permissions(root, application)

    def test_exact_nonexported_alert_components_are_accepted(self):
        _case, fixture = self.source_fixture()
        self.assert_source_components(*fixture)

    def test_alert_component_exposure_filter_and_attribute_mutations_are_rejected(self):
        for mutation in ("exported", "third", "process", "filter", "data", "category", "extra_action"):
            with self.subTest(mutation=mutation):
                _case, (root, application) = self.source_fixture()
                receiver = application.find(f"receiver[@{A}name='.alert.EventAlertReceiver']")
                restore = application.find(f"receiver[@{A}name='.alert.AlertRestoreReceiver']")
                if mutation == "exported":
                    receiver.set(A + "exported", "true")
                elif mutation == "third":
                    ET.SubElement(application, "receiver", {A + "name": ".alert.OtherReceiver", A + "exported": "false"})
                elif mutation == "process":
                    receiver.set(A + "process", ":other")
                elif mutation == "filter":
                    ET.SubElement(receiver, "intent-filter")
                else:
                    tag = "action" if mutation == "extra_action" else mutation
                    ET.SubElement(restore.find("intent-filter"), tag, {A + "name": "unexpected"})
                with self.assertRaises(AssertionError):
                    self.assert_source_components(root, application)

    def test_merged_exact_alert_permissions_components_and_filters_are_accepted(self):
        case = merged_policy.MergedManifestSecurityTests()
        fixture = foreground_alarm_manifest(case._manifest("debug")[0], merged=True)
        with patch.object(case, "VARIANTS", {"fixture": "unused"}), \
                patch.object(case, "_manifest", return_value=fixture):
            case.test_merged_permissions_are_an_exact_allowlist()
            case.test_merged_components_are_an_exact_hardened_allowlist()

    def check_factories(self, mutation=None):
        with tempfile.TemporaryDirectory(dir=ANDROID_ROOT / ".tmp", prefix="t66-policy-") as directory:
            root = Path(directory)
            kotlin = root / "app/src/main/java/com/molotov/clender"
            widget = kotlin / "widget/WidgetPendingIntentFactory.kt"
            alert = kotlin / "alert/AlertPendingIntents.kt"
            platform = kotlin / "alert/PlatformEventAlerts.kt"
            for file in (widget, alert, platform):
                file.parent.mkdir(parents=True, exist_ok=True)
            factory = """val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
PendingIntent.getActivity(context, 0, intent, flags)
PendingIntent.getBroadcast(context, 0, intent, flags)
"""
            widget.write_text(factory, encoding="utf-8")
            alert.write_text(factory, encoding="utf-8")
            platform.write_text("AlarmManager NotificationManager", encoding="utf-8")
            if mutation == "mutable":
                alert.write_text(factory.replace("FLAG_IMMUTABLE", "FLAG_MUTABLE"), encoding="utf-8")
            elif mutation == "duplicate":
                alert.write_text(factory + "PendingIntent.getBroadcast(context, 0, intent, flags)", encoding="utf-8")
            elif mutation == "service":
                alert.write_text(factory + "PendingIntent.getService(context, 0, intent, flags)", encoding="utf-8")
            elif mutation == "elsewhere":
                (kotlin / "Other.kt").write_text("AlarmManager NotificationManager", encoding="utf-8")
            case = source_policy.ManifestSecurityTests()
            renderer = "setOnClickPendingIntent\n" * 4 + "R.id.widget_configure R.id.widget_event_row R.id.widget_refresh"
            with patch.object(case, "path", side_effect=lambda relative: root / relative), \
                    patch.object(case, "read_text", return_value=renderer):
                case._assert_widget_pending_intent_surface()

    def test_exact_alert_platform_and_immutable_factories_are_accepted(self):
        self.check_factories()

    def test_mutable_extra_factories_and_platform_escapes_are_rejected(self):
        for mutation in ("mutable", "duplicate", "service", "elsewhere"):
            with self.subTest(mutation=mutation), self.assertRaises(AssertionError):
                self.check_factories(mutation)
