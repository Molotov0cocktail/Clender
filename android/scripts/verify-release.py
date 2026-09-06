"""Offline release auditing and explicit, non-overwriting local key creation.

Run through the PowerShell entrypoints to reuse env.ps1 isolation. No downloads,
Gradle execution or key generation occur on import. Tool output is captured and
never printed on failure. CLI errors intentionally contain no paths or secrets.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import secrets
import struct
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile
import zlib


ROOT = Path(__file__).resolve().parents[1]
NS = "{http://schemas.android.com/apk/res/android}"
SIGNING = dict(zip(
    ("CLENDER_ANDROID_KEYSTORE_FILE", "CLENDER_ANDROID_KEYSTORE_PASSWORD",
     "CLENDER_ANDROID_KEY_ALIAS", "CLENDER_ANDROID_KEY_PASSWORD"),
    ("storeFile", "storePassword", "keyAlias", "keyPassword"),
))


class ReleaseError(Exception):
    def __init__(self, message, exit_code=1):
        super().__init__(message)
        self.exit_code = exit_code


def resolve_inside(root, path):
    root = Path(root).resolve()
    candidate = (root / path).resolve()
    if candidate == root or not candidate.is_relative_to(root):
        raise ReleaseError("Path is outside the isolated Android directory")
    return candidate


def run_tool(argv, *, cwd, env=None):
    try:
        result = subprocess.run(
            list(map(str, argv)), cwd=cwd, env=env, shell=False,
            capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=300,
        )
    except subprocess.TimeoutExpired:
        raise ReleaseError("Tool timed out", 124) from None
    except OSError:
        raise ReleaseError("Tool could not start", 127) from None
    if result.returncode:
        raise ReleaseError("Tool failed", result.returncode)
    return result


def validate_signing(root, values, *, is_ignored):
    if any(not isinstance(values.get(name), str) or not values[name].strip() for name in SIGNING):
        raise ReleaseError("Release signing credentials are required")
    key = resolve_inside(root, values["CLENDER_ANDROID_KEYSTORE_FILE"])
    if (not key.is_file() or key.name.lower() == "debug.keystore"
            or values["CLENDER_ANDROID_KEY_ALIAS"].lower() == "androiddebugkey" or not is_ignored(key)):
        raise ReleaseError("Independent ignored release signing credentials are required")
    return key


def ignored(root, path):
    try:
        run_tool(["git", "check-ignore", "-q", "--no-index", "--", path], cwd=root)
        return True
    except ReleaseError as error:
        if error.exit_code == 1:
            return False
        raise


def read_signing(root):
    # Reads only the explicitly supported ignored properties file, never key bytes.
    properties = resolve_inside(root, "keystore.properties")
    local = {}
    if properties.exists():
        if not ignored(root, properties):
            raise ReleaseError("Signing configuration must be ignored")
        text = properties.read_text(encoding="iso-8859-1")
        text = re.sub(r"\\\r?\n[ \t]*", "", text)
        for line in text.splitlines():
            if not line.strip() or line.lstrip().startswith(("#", "!")):
                continue
            match = re.match(r"\s*(storeFile|storePassword|keyAlias|keyPassword)\s*[=:]\s*(.*)$", line)
            if not match:
                raise ReleaseError("Unsupported signing property syntax")
            value = re.sub(r"\\u([0-9a-fA-F]{4})|\\(.)", lambda m:
                           chr(int(m[1], 16)) if m[1] else {"t": "\t", "n": "\n", "r": "\r", "f": "\f"}.get(m[2], m[2]), match[2])
            # Java Properties escapes represent UTF-16 code units, including pairs.
            local[match[1]] = value.encode("utf-16-be", errors="surrogatepass").decode(
                "utf-16-be", errors="surrogatepass"
            )
    return {name: os.environ[name] if os.environ.get(name, "").strip() else local.get(prop, "")
            for name, prop in SIGNING.items()}


def create_release_key(root, key, properties, *, runner=run_tool):
    key, properties = resolve_inside(root, key), resolve_inside(root, properties)
    if key == properties or key.name.lower() == "debug.keystore":
        raise ReleaseError("Independent key destinations are required")
    if os.path.lexists(key) or os.path.lexists(properties):
        raise ReleaseError("Existing signing material will not be overwritten")
    key.parent.mkdir(parents=True, exist_ok=True)
    properties.parent.mkdir(parents=True, exist_ok=True)
    scratch = resolve_inside(root, ".tmp")
    scratch.mkdir(exist_ok=True)
    store_password, key_password = secrets.token_urlsafe(48), secrets.token_urlsafe(48)
    alias = "release-" + secrets.token_hex(16)
    environment = dict(os.environ, CLENDER_NEW_STORE_PASSWORD=store_password, CLENDER_NEW_KEY_PASSWORD=key_password)
    try:
        with tempfile.TemporaryDirectory(prefix="new-release-", dir=scratch) as temporary:
            staged_key = Path(temporary) / "key.jks"
            staged_config = Path(temporary) / "signing.properties"
            runner([
                resolve_inside(root, ".toolchain/jdk-17.0.20+8/bin/keytool.exe"),
                "-genkeypair", "-storetype", "JKS", "-keystore", staged_key,
                "-alias", alias, "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
                "-dname", "CN=Clender Release", "-storepass:env", "CLENDER_NEW_STORE_PASSWORD",
                "-keypass:env", "CLENDER_NEW_KEY_PASSWORD", "-noprompt",
            ], cwd=root, env=environment)
            if not staged_key.is_file() or not staged_key.stat().st_size:
                raise ReleaseError("Key tool did not produce a key")
            relative_key = key.relative_to(Path(root).resolve()).as_posix()
            # Java Properties uses exactly four hex digits per UTF-16 code unit.
            utf16_key = relative_key.encode("utf-16-be")
            units = (int.from_bytes(utf16_key[i:i + 2], "big") for i in range(0, len(utf16_key), 2))
            escaped_key = "".join("\\u%04x" % unit if unit > 127 else "\\" + chr(unit)
                                  if chr(unit) in "\\ :=#!" else chr(unit) for unit in units)
            staged_config.write_text(
                f"storeFile={escaped_key}\nstorePassword={store_password}\nkeyAlias={alias}\nkeyPassword={key_password}\n",
                encoding="ascii",
            )
            os.chmod(staged_key, 0o600)
            os.chmod(staged_config, 0o600)
            # Atomic create-if-absent, including races after preflight. Never replace.
            # If config publication fails, preserve the published key for recovery.
            os.link(staged_key, key)
            os.link(staged_config, properties)
    except OSError:
        raise ReleaseError("Signing publication failed; existing files were preserved") from None
    finally:
        environment.pop("CLENDER_NEW_STORE_PASSWORD", None)
        environment.pop("CLENDER_NEW_KEY_PASSWORD", None)


FORBIDDEN_CODE_MARKERS = (
    b"faketransport", b"testhook", b"mockwebserver", b"org/robolectric/",
    b"androidx/test/", b"com/google/firebase/", b"com/google/android/gms/",
    b"com/google/android/play/", b"com/google/mlkit/", b"androidx/glance/",
)
PRIVATE_KEY_MARKER = re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----")
MAX_ARCHIVE_ENTRY_BYTES = 128 * 1024 * 1024


def audit_dex(data):
    """Audit standard DEX type references/class definitions, not string constants.

    DEX 035/037/038/039/040 use the 112-byte header. Unsupported versions,
    inconsistent header/map/table boundaries and malformed referenced descriptors
    fail closed. This is a bounded type audit, not an ART bytecode verifier.
    All type_ids are checked, including types referenced but not defined here.
    Logger names (or other ordinary string_ids) are not evidence of class code.
    R8 mapping and non-DEX resource checks remain separate release gates.
    """
    size = len(data)
    if size < 112 or size > MAX_ARCHIVE_ENTRY_BYTES or data[:8] not in (
        b"dex\n035\x00", b"dex\n037\x00", b"dex\n038\x00", b"dex\n039\x00", b"dex\n040\x00",
    ):
        raise ReleaseError("Unsupported or truncated DEX header")

    def uint(offset):
        if offset < 0 or offset + 4 > size:
            raise ReleaseError("DEX integer is outside the artifact")
        return struct.unpack_from("<I", data, offset)[0]

    if uint(32) != size or uint(36) != 112 or uint(40) != 0x12345678:
        raise ReleaseError("Invalid DEX header size or byte order")
    if uint(44) or uint(48):
        raise ReleaseError("Linked DEX files are unsupported")
    if uint(8) != zlib.adler32(memoryview(data)[12:]) & 0xFFFFFFFF:
        raise ReleaseError("DEX checksum mismatch")
    if data[12:32] != hashlib.sha1(memoryview(data)[32:]).digest():
        raise ReleaseError("DEX signature mismatch")
    data_size, data_off = uint(104), uint(108)
    if not data_size or data_off < 112 or data_off % 4 or data_off + data_size != size:
        raise ReleaseError("Invalid DEX data section")

    tables, spans = {}, [(0, 112)]
    for kind, header, width, limit in (
        (1, 56, 4, 1_000_000), (2, 64, 4, 65535), (3, 72, 12, 65535),
        (4, 80, 8, 65535), (5, 88, 8, 65535), (6, 96, 32, 65535),
    ):
        count, offset = uint(header), uint(header + 4)
        if count > limit or (count == 0) != (offset == 0):
            raise ReleaseError("Invalid DEX table count")
        if count:
            end = offset + count * width
            if offset < 112 or offset % 4 or end > data_off:
                raise ReleaseError("DEX table is outside its section")
            spans.append((offset, end))
        tables[kind] = (count, offset)
    spans.sort()
    if any(left[1] > right[0] for left, right in zip(spans, spans[1:])):
        raise ReleaseError("Overlapping DEX tables")

    map_off = uint(52)
    if map_off < data_off or map_off % 4 or map_off + 4 > size:
        raise ReleaseError("Invalid DEX map location")
    map_count = uint(map_off)
    if not 1 <= map_count <= 64 or map_off + 4 + map_count * 12 > size:
        raise ReleaseError("Invalid DEX map size")
    mappings, previous_offset = {}, -1
    for index in range(map_count):
        kind, reserved, count, offset = struct.unpack_from("<HHII", data, map_off + 4 + index * 12)
        if reserved or not count or kind in mappings or offset <= previous_offset or offset >= size:
            raise ReleaseError("Invalid DEX map entry")
        mappings[kind] = (count, offset)
        previous_offset = offset
    if mappings.get(0) != (1, 0) or mappings.get(0x1000) != (1, map_off):
        raise ReleaseError("DEX map does not describe its header")
    for kind, table in tables.items():
        if mappings.get(kind, (0, 0)) != table:
            raise ReleaseError("DEX header and map tables differ")

    string_count, string_off = tables[1]
    type_count, type_off = tables[2]
    class_count, class_off = tables[6]
    if class_count > type_count or type_count > string_count:
        raise ReleaseError("Invalid DEX type/class counts")
    string_section = mappings.get(0x2002)
    if string_count and (string_section is None or string_section[0] != string_count):
        raise ReleaseError("Invalid DEX string section")
    string_end = size
    if string_section:
        string_start = string_section[1]
        if string_start < data_off:
            raise ReleaseError("Invalid DEX string data start")
        string_end = min((offset for _, offset in mappings.values() if offset > string_start), default=size)
        if any(not string_start <= uint(string_off + index * 4) < string_end for index in range(string_count)):
            raise ReleaseError("DEX string offset is outside string data")

    descriptor_bytes = 0

    def descriptor(string_index):
        nonlocal descriptor_bytes
        if string_index >= string_count:
            raise ReleaseError("DEX type has an invalid string index")
        offset = uint(string_off + 4 * string_index)
        utf16_size = 0
        for shift in range(0, 35, 7):
            if offset >= string_end:
                raise ReleaseError("Truncated DEX string length")
            byte = data[offset]
            offset += 1
            if shift == 28 and byte > 15:
                raise ReleaseError("Overflowing DEX string length")
            utf16_size |= (byte & 127) << shift
            if byte < 128:
                break
        else:
            raise ReleaseError("Unterminated DEX string length")
        end = data.find(b"\x00", offset, min(string_end, offset + 65537))
        if end < 0 or utf16_size > 65536:
            raise ReleaseError("DEX descriptor exceeds size limit")
        descriptor_bytes += end - offset
        if descriptor_bytes > 16 * 1024 * 1024:
            raise ReleaseError("DEX descriptor audit budget exceeded")
        encoded = data[offset:end]
        try:
            # MUTF-8 encodes UTF-16 code units, including surrogate pairs.
            if any(byte >= 0xF0 for byte in encoded):
                raise ValueError()
            value = encoded.replace(b"\xc0\x80", b"\x00").decode("utf-8", errors="surrogatepass")
        except (UnicodeError, ValueError):
            raise ReleaseError("Invalid DEX descriptor encoding") from None
        if len(value) != utf16_size:
            raise ReleaseError("DEX descriptor length mismatch")
        dimensions = len(value) - len(value.lstrip("["))
        base = value[dimensions:]
        if dimensions > 255 or not base:
            raise ReleaseError("Invalid DEX array descriptor")
        if base in ("V", "Z", "B", "S", "C", "I", "J", "F", "D"):
            if dimensions and base == "V":
                raise ReleaseError("Void array is not a DEX type")
        elif base.startswith("L") and base.endswith(";"):
            name = base[1:-1]
            if not name or any(not part for part in name.split("/")) or any(
                char.isspace() or char in ".;[\x00" for char in name
            ):
                raise ReleaseError("Invalid DEX class descriptor")
        else:
            raise ReleaseError("Invalid DEX type descriptor")
        if any(marker in encoded.lower() for marker in FORBIDDEN_CODE_MARKERS):
            raise ReleaseError("Forbidden test or service DEX type")
        return value

    types, previous_string = [], -1
    for index in range(type_count):
        string_index = uint(type_off + index * 4)
        if string_index <= previous_string:
            raise ReleaseError("Unsorted or duplicate DEX type index")
        types.append(descriptor(string_index))
        previous_string = string_index

    defined_classes, checked_interfaces = set(), set()
    interface_entries = 0
    for index in range(class_count):
        row = class_off + index * 32
        class_index, _, superclass, interfaces, source, annotations, members, values = struct.unpack_from("<8I", data, row)
        if class_index in defined_classes or class_index >= type_count or not types[class_index].startswith("L"):
            raise ReleaseError("Invalid DEX class definition")
        defined_classes.add(class_index)
        if superclass != 0xFFFFFFFF and (superclass >= type_count or not types[superclass].startswith("L")):
            raise ReleaseError("Invalid DEX superclass")
        if source != 0xFFFFFFFF and source >= string_count:
            raise ReleaseError("Invalid DEX source index")
        if any(offset and not data_off <= offset < size for offset in (interfaces, annotations, members, values)):
            raise ReleaseError("DEX class data is outside its section")
        if interfaces and interfaces not in checked_interfaces:
            if interfaces % 4 or interfaces + 4 > size:
                raise ReleaseError("Invalid DEX interface list")
            count = uint(interfaces)
            if count > type_count or interfaces + 4 + 2 * count > size:
                raise ReleaseError("Invalid DEX interface count")
            interface_entries += count
            if interface_entries > 1_000_000:
                raise ReleaseError("DEX interface audit budget exceeded")
            checked_interfaces.add(interfaces)
            seen_interfaces = set()
            for item in range(count):
                type_index = struct.unpack_from("<H", data, interfaces + 4 + item * 2)[0]
                if type_index in seen_interfaces or type_index >= type_count or not types[type_index].startswith("L"):
                    raise ReleaseError("Invalid DEX interface type")
                seen_interfaces.add(type_index)


def audit_archive(path):
    try:
        with zipfile.ZipFile(path) as archive:
            seen, total = set(), 0
            for entry in archive.infolist():
                name = entry.filename.replace("\\", "/")
                normalized = name.lower()
                if name in seen or name.startswith("/") or ":" in name or ".." in PurePosixPath(name).parts:
                    raise ReleaseError("Unsafe or duplicate archive entry")
                seen.add(name)
                if normalized.endswith((".so", ".dll", ".dylib", ".jks", ".keystore", ".p12", ".pfx")):
                    raise ReleaseError("Native library or signing material in artifact")
                if any(marker in normalized for marker in ("test_ca", "test-ca", "keystore.properties", "fake_transport")):
                    raise ReleaseError("Test or secret resource in artifact")
                total += entry.file_size
                if entry.file_size > MAX_ARCHIVE_ENTRY_BYTES or total > 512 * 1024 * 1024:
                    raise ReleaseError("Archive exceeds audit size limit")
                with archive.open(entry) as stream:
                    first = stream.read(8)
                    if normalized.endswith(".dex") or first.startswith(b"dex\n"):
                        dex = first + stream.read(MAX_ARCHIVE_ENTRY_BYTES + 1 - len(first))
                        if len(dex) != entry.file_size:
                            raise ReleaseError("DEX archive entry size mismatch")
                        audit_dex(dex)
                        if PRIVATE_KEY_MARKER.search(dex):
                            raise ReleaseError("Private key in artifact")
                        continue
                    tail = b""
                    chunk = first
                    while chunk:
                        payload = tail + chunk
                        if any(marker in payload.lower() for marker in FORBIDDEN_CODE_MARKERS):
                            raise ReleaseError("Forbidden test or service code in artifact")
                        if PRIVATE_KEY_MARKER.search(payload):
                            raise ReleaseError("Private key in artifact")
                        tail = payload[-128:]
                        chunk = stream.read(65536)
    except (OSError, zipfile.BadZipFile, RuntimeError):
        raise ReleaseError("Artifact archive could not be audited") from None


def audit_badging(text):
    """Require one exact package and one numeric API-26 minimum SDK record.

    Build Tools 36 emits minSdkVersion; older aapt versions used sdkVersion.
    Neither a target-SDK line nor a substring in another field can satisfy this
    gate. Repeated records (including aliases) fail closed even when equal.
    """
    lines = [line.strip() for line in text.splitlines()]
    packages = [line for line in lines if line.startswith("package:")]
    if len(packages) != 1:
        raise ReleaseError("APK package record is missing or ambiguous")
    names = re.findall(r"(?:^|\s)name='([^']*)'", packages[0])
    if names != ["com.molotov.clender"] or not re.match(
        r"^package:\s+name='com\.molotov\.clender'(?:\s|$)", packages[0]
    ):
        raise ReleaseError("APK package mismatch")
    minimums = [line for line in lines if re.match(r"^(?:minSdkVersion|sdkVersion)\b", line)]
    if len(minimums) != 1 or not re.fullmatch(r"(?:minSdkVersion|sdkVersion):\s*'26'", minimums[0]):
        raise ReleaseError("APK minimum SDK must be one exact API 26 record")
    return 26


def audit_manifest(xml):
    if "<!DOCTYPE" in xml or "<!ENTITY" in xml:
        raise ReleaseError("XML declarations are forbidden")
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        raise ReleaseError("Decoded manifest is invalid") from None
    apps = root.findall("application")
    if root.tag != "manifest" or len(apps) != 1 or root.get("package") != "com.molotov.clender":
        raise ReleaseError("Release manifest identity is invalid")
    app = apps[0]
    if any(app.get(NS + flag, "false") != "false" for flag in ("debuggable", "testOnly")):
        raise ReleaseError("Debug or test release manifest is forbidden")
    if app.get(NS + "usesCleartextTraffic") != "false" or root.find("instrumentation") is not None:
        raise ReleaseError("Unsafe release manifest")
    return root


def decode_xmltree(text):
    """Decode aapt2's actual binary-XML tree dump, preserving element ownership."""
    stack, root = [], None
    for line in text.splitlines():
        binding = re.match(r"^\s*N:\s+android=(\S+)", line)
        if binding and binding[1] != NS[1:-1]:
            raise ReleaseError("Unexpected Android namespace binding")
        element = re.match(r"^(\s*)E: ([\w-]+)(?:\s|$)", line)
        if element:
            indent = len(element[1])
            node = ET.Element(element[2])
            while stack and stack[-1][0] >= indent:
                stack.pop()
            if stack:
                stack[-1][1].append(node)
            elif root is None:
                root = node
            else:
                raise ReleaseError("Unexpected binary XML tree")
            stack.append((indent, node))
        # Build Tools 36 prints the namespace URI itself, not only `android:`.
        # Split at the LAST colon so the URI scheme and slash characters survive.
        attribute = re.match(r"^\s*A: ([^\s=()]+)(?:\(0x[0-9a-fA-F]+\))?=(.*)$", line)
        if line.lstrip().startswith("A:") and (attribute is None or not stack):
            raise ReleaseError("Malformed artifact XML attribute")
        if attribute and stack:
            name, value = attribute[1], attribute[2].strip()
            if value.startswith('"'):
                value = value.split('"', 2)[1]
            elif value.startswith("(type 0x12)"):
                value = "false" if int(value.split(")", 1)[1], 16) == 0 else "true"
            if ":" in name:
                namespace, local_name = name.rsplit(":", 1)
                namespace = NS[1:-1] if namespace == "android" else namespace
                if not re.fullmatch(r"[\w.-]+", local_name):
                    raise ReleaseError("Invalid artifact XML attribute name")
                name = "{" + namespace + "}" + local_name
            if name in stack[-1][1].attrib:
                raise ReleaseError("Duplicate artifact XML attribute")
            stack[-1][1].set(name, value)
    if root is None:
        raise ReleaseError("Artifact XML dump was empty")
    return ET.tostring(root, encoding="unicode")


def audit_network(xml):
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        raise ReleaseError("Invalid network security XML") from None
    bases = root.findall("base-config")
    if root.tag != "network-security-config" or len(bases) != 1 or bases[0].get("cleartextTrafficPermitted") != "false":
        raise ReleaseError("Unsafe network security policy")
    if root.find("debug-overrides") is not None:
        raise ReleaseError("Debug trust overrides in release")
    if any(node.get("cleartextTrafficPermitted", "false") != "false" for node in root.iter()):
        raise ReleaseError("Cleartext network override in release")
    certificates = list(root.iter("certificates"))
    if not certificates or any(node.get("src") != "system" for node in certificates):
        raise ReleaseError("Only system certificate authorities are permitted")


def decode_proto_xml(data):
    """Decode AAPT Resources.proto XmlNode, using the locally locked schema.

    Only XML structure/string and compiled boolean fields are consumed. Unknown
    policy values remain unresolved and are rejected by audit_network.
    """
    def fields(payload):
        offset, result = 0, {}
        def varint():
            nonlocal offset
            value = 0
            for shift in range(0, 70, 7):
                if offset >= len(payload):
                    raise ReleaseError("Truncated protobuf XML")
                byte = payload[offset]
                offset += 1
                value |= (byte & 127) << shift
                if byte < 128:
                    return value
            raise ReleaseError("Invalid protobuf integer")
        while offset < len(payload):
            tag = varint()
            number, wire = tag >> 3, tag & 7
            if not number:
                raise ReleaseError("Invalid protobuf field")
            if wire == 0:
                value = varint()
            elif wire in (1, 2, 5):
                size = varint() if wire == 2 else (8 if wire == 1 else 4)
                if offset + size > len(payload):
                    raise ReleaseError("Truncated protobuf field")
                value = payload[offset:offset + size]
                offset += size
            else:
                raise ReleaseError("Unsupported protobuf wire type")
            result.setdefault(number, []).append(value)
        return result
    def one(values, number, default=b""):
        matches = values.get(number, [default])
        if len(matches) != 1:
            raise ReleaseError("Duplicate protobuf scalar")
        return matches[0]
    def text(values, number):
        return one(values, number).decode("utf-8")
    def node(payload, depth=0):
        if depth > 40:
            raise ReleaseError("XML nesting limit exceeded")
        message = fields(payload)
        if 1 not in message:
            if 2 in message:
                return None
            raise ReleaseError("Missing protobuf XML element")
        element = fields(one(message, 1))
        name = text(element, 3)
        if not name:
            raise ReleaseError("Missing protobuf XML name")
        result = ET.Element(name)
        for payload in element.get(4, []):
            attribute = fields(payload)
            name, namespace, value = text(attribute, 2), text(attribute, 1), text(attribute, 3)
            if 6 in attribute:
                compiled = fields(one(attribute, 6))
                for string_field in (2, 3):
                    if string_field in compiled:
                        value = text(fields(one(compiled, string_field)), 1)
                if 7 in compiled:
                    primitive = fields(one(compiled, 7))
                    if 8 in primitive:
                        value = "true" if one(primitive, 8) else "false"
            name = "{" + namespace + "}" + name if namespace else name
            if name in result.attrib:
                raise ReleaseError("Duplicate XML attribute")
            result.set(name, value)
        for payload in element.get(5, []):
            child = node(payload, depth + 1)
            if child is not None:
                result.append(child)
        return result
    try:
        if len(data) > 1024 * 1024:
            raise ReleaseError("XML exceeds size limit")
        root = node(data)
        if root is None:
            raise ReleaseError("Missing protobuf root")
        return ET.tostring(root, encoding="unicode")
    except (UnicodeError, AttributeError, TypeError):
        raise ReleaseError("Invalid protobuf XML") from None


def artifact_metadata(root, path):
    path = resolve_inside(root, path)
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return {"path": path.relative_to(Path(root).resolve()).as_posix(),
            "size_bytes": path.stat().st_size, "sha256": digest.hexdigest()}


def write_metadata(root, destination, artifacts, *, evidence=None):
    destination = resolve_inside(root, destination)
    if destination.suffix.lower() != ".json" or destination in [resolve_inside(root, path) for path in artifacts]:
        raise ReleaseError("Metadata must use a separate JSON output")
    document = {"artifacts": [artifact_metadata(root, path) for path in artifacts], "evidence": evidence or {}}
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=destination.parent, delete=False) as output:
        temporary = Path(output.name)
        json.dump(document, output, indent=2, ensure_ascii=True)
        output.write("\n")
    try:
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)


def audit_release(root, apk, aab, bundletool, metadata, *, runner=run_tool):
    apk, aab, bundletool = (resolve_inside(root, path) for path in (apk, aab, bundletool))
    java = resolve_inside(root, ".toolchain/jdk-17.0.20+8/bin/java.exe")
    jdk = java.parent
    build_tools = resolve_inside(root, ".sdk/build-tools/36.0.0")
    for tool in (java, jdk / "jarsigner.exe", jdk / "keytool.exe", build_tools / "aapt2.exe",
                 build_tools / "zipalign.exe", build_tools / "lib/apksigner.jar", bundletool):
        if not tool.is_file():
            raise ReleaseError("A required isolated release tool is missing")
    def call(argv):
        return runner(argv, cwd=root).stdout
    java_args = [java, "-Duser.language=en", "-Duser.country=US", "-Duser.home=" + str(resolve_inside(root, ".android"))]
    for artifact in (apk, aab):
        audit_archive(artifact)
    mapping = resolve_inside(root, "app/build/outputs/mapping/release/mapping.txt")
    if not mapping.is_file() or not mapping.stat().st_size:
        raise ReleaseError("Release R8 mapping is required")
    mapping_text = mapping.read_text(encoding="utf-8").lower().replace(".", "/")
    if any(marker in mapping_text for marker in ("faketransport", "testhook", "mockwebserver", "androidx/test/", "org/robolectric/")):
        raise ReleaseError("Test implementation found in R8 input mapping")
    signature = call(java_args + ["-jar", build_tools / "lib/apksigner.jar", "verify", "--verbose",
                                 "--print-certs", "--min-sdk-version", "26", apk])
    hashes = re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})", signature)
    if len(hashes) != 1 or "android debug" in signature.lower():
        raise ReleaseError("Independent single-signer APK certificate required")
    call([build_tools / "zipalign.exe", "-c", "-v", "4", apk])
    badging = call([build_tools / "aapt2.exe", "dump", "badging", apk])
    audit_badging(badging)
    manifest = call([build_tools / "aapt2.exe", "dump", "xmltree", "--file", "AndroidManifest.xml", apk])
    audit_manifest(decode_xmltree(manifest))
    # Inspect all XML resources, so another name or qualifier cannot hide a trust override.
    with zipfile.ZipFile(apk) as archive:
        resources = [name for name in archive.namelist() if name.startswith("res/") and name.endswith(".xml")]
    network_count = 0
    for name in resources:
        xml = decode_xmltree(call([build_tools / "aapt2.exe", "dump", "xmltree", "--file", name, apk]))
        if ET.fromstring(xml).tag == "network-security-config":
            audit_network(xml)
            network_count += 1
    if not network_count:
        raise ReleaseError("Packaged network security policy is missing")
    bundle_args = java_args + ["-jar", bundletool]
    call(bundle_args + ["validate", "--bundle=" + str(aab)])
    audit_manifest(call(bundle_args + ["dump", "manifest", "--bundle=" + str(aab), "--module=base"]))
    with zipfile.ZipFile(aab) as archive:
        network_count = 0
        for name in archive.namelist():
            if "/res/" in name and name.endswith(".xml"):
                xml = decode_proto_xml(archive.read(name))
                if ET.fromstring(xml).tag == "network-security-config":
                    audit_network(xml)
                    network_count += 1
        if not network_count:
            raise ReleaseError("AAB network security policy is missing")
    jar_signature = call([jdk / "jarsigner.exe", "-J-Duser.language=en", "-J-Duser.country=US", "-verify", "-verbose", "-certs", aab])
    if "jar verified." not in jar_signature.lower() or "unsigned entries" in jar_signature.lower():
        raise ReleaseError("AAB signature verification failed")
    cert_text = call([jdk / "keytool.exe", "-printcert", "-jarfile", aab, "-rfc"])
    certs = re.findall(r"-----BEGIN CERTIFICATE-----\s*(.*?)\s*-----END CERTIFICATE-----", cert_text, re.S)
    if len(certs) != 1 or hashlib.sha256(base64.b64decode(certs[0])).hexdigest() != hashes[0].lower():
        raise ReleaseError("APK and AAB signing certificates differ")
    write_metadata(root, metadata, [apk, aab], evidence={
        "certificate_sha256": hashes[0].lower(), "apk_min_sdk": 26,
        "build_tools": "36.0.0", "bundletool": artifact_metadata(root, bundletool),
        "r8_mapping": artifact_metadata(root, mapping),
        "checks": ["apksigner", "zipalign", "aapt2-artifact-manifest", "artifact-network-policy",
                   "bundletool-validate", "bundletool-manifest", "jarsigner", "matching-certificates", "archive-audit"],
        "device_validation": "NOT_PERFORMED_BY_THIS_SCRIPT",
    })


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("signing-check")
    key = commands.add_parser("new-key")
    key.add_argument("--key", default="release/clender-release.jks")
    key.add_argument("--properties", default="keystore.properties")
    verify = commands.add_parser("verify")
    verify.add_argument("--apk", default="app/build/outputs/apk/release/app-release.apk")
    verify.add_argument("--aab", default="app/build/outputs/bundle/release/app-release.aab")
    verify.add_argument("--bundletool", default=".toolchain/bundletool-all.jar")
    verify.add_argument("--metadata", default="release/artifact-metadata.json")
    args = parser.parse_args()
    try:
        if args.command == "signing-check":
            validate_signing(ROOT, read_signing(ROOT), is_ignored=lambda path: ignored(ROOT, path))
        elif args.command == "new-key":
            for path in (args.key, args.properties):
                if not ignored(ROOT, resolve_inside(ROOT, path)):
                    raise ReleaseError("Signing destinations must be ignored")
            create_release_key(ROOT, args.key, args.properties)
        else:
            audit_release(ROOT, args.apk, args.aab, args.bundletool, args.metadata)
        print("Release tool completed successfully.")
        return 0
    except ReleaseError as error:
        print(str(error))
        return error.exit_code
    except Exception:
        print("Release tool failed; details suppressed to protect signing inputs.")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
