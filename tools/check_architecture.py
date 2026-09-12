#!/usr/bin/env python3
"""Automated architecture guardrail checker for Jarvis.

Checks:
1. Feature-to-Feature isolation: No :feature:* module may depend on another :feature:* module.
2. API Key isolation: API keys must only be handled through ApiKeyStore; no room entities or unencrypted storage may store API keys.
3. JUnit 5 test discovery: Modules with unit tests must configure useJUnitPlatform() and include junit-platform-launcher.
"""

import sys
from pathlib import Path
import re

# Windows consoles default to cp1252, which cannot print emoji/box glyphs
# used here and crashes with UnicodeEncodeError. Force UTF-8 stdout/stderr.
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent

def check_feature_dependencies():
    print("[1/3] Checking feature-to-feature dependencies...")
    features_dir = ROOT / "feature"
    if not features_dir.exists():
        return

    violations = []
    for gradle_file in features_dir.glob("*/build.gradle.kts"):
        content = gradle_file.read_text(encoding="utf-8")
        matches = re.findall(r'project\(["\']:feature:([^"\']+)["\']\)', content)
        if matches:
            violations.append(f"{gradle_file.relative_to(ROOT)} depends directly on feature(s): {matches}")

    if violations:
        for v in violations:
            print(f"  [FAIL] {v}", file=sys.stderr)
        return False
    print("  [PASS] All feature modules are isolated from each other.")
    return True

def check_api_key_boundaries():
    print("[2/3] Checking API key storage boundaries...")
    # Check that Room entities in :core:database do not declare apiKey columns
    database_dir = ROOT / "core" / "database" / "src" / "main"
    violations = []
    for entity_file in database_dir.glob("**/entity/*.kt"):
        content = entity_file.read_text(encoding="utf-8")
        if re.search(r'val\s+apiKey\b', content) or re.search(r'var\s+apiKey\b', content):
            violations.append(f"Room entity {entity_file.name} defines an apiKey column! API keys must live exclusively in ApiKeyStore.")

    if violations:
        for v in violations:
            print(f"  [FAIL] {v}", file=sys.stderr)
        return False
    print("  [PASS] No API key storage detected in Room database entities.")
    return True

def check_junit5_configuration():
    print("[3/3] Checking JUnit 5 & launcher configuration...")
    violations = []
    for gradle_file in ROOT.glob("**/build.gradle.kts"):
        if gradle_file == ROOT / "build.gradle.kts":
            continue
        content = gradle_file.read_text(encoding="utf-8")
        has_tests = "testImplementation" in content
        if has_tests:
            if "useJUnitPlatform()" not in content:
                violations.append(f"{gradle_file.relative_to(ROOT)} has tests but is missing testOptions {{ unitTests.all {{ it.useJUnitPlatform() }} }}")
            if "junit.platform.launcher" not in content and "junit-platform-launcher" not in content:
                violations.append(f"{gradle_file.relative_to(ROOT)} has tests but is missing testRuntimeOnly(libs.junit.platform.launcher)")

    if violations:
        for v in violations:
            print(f"  [FAIL] {v}", file=sys.stderr)
        return False
    print("  [PASS] All test-enabled modules configure JUnit 5 and the platform launcher.")
    return True

def main():
    print("=== Jarvis Architecture Guardrails Check ===")
    ok = True
    ok = check_feature_dependencies() and ok
    ok = check_api_key_boundaries() and ok
    ok = check_junit5_configuration() and ok

    if not ok:
        print("\n[FAIL] Architecture check failed! Fix the violations above.", file=sys.stderr)
        sys.exit(1)
    print("\n[PASS] All architecture guardrails passed successfully.")

if __name__ == "__main__":
    main()
