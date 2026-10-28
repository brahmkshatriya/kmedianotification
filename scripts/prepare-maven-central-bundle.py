#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import shutil
import xml.etree.ElementTree as ElementTree
from pathlib import Path
from zipfile import ZipFile

IGNORED_SUFFIXES = (".asc", ".md5", ".sha1", ".sha256", ".sha512")
POM_NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}


def element_text(root: ElementTree.Element, path: str) -> str:
    element = root.find(path, POM_NAMESPACE)
    return element.text.strip() if element is not None and element.text else ""


def validate_pom(pom: Path, release_version: str) -> None:
    root = ElementTree.parse(pom).getroot()
    required = (
        "m:groupId", "m:artifactId", "m:version", "m:name", "m:description", "m:url",
        "m:licenses/m:license/m:name", "m:licenses/m:license/m:url",
        "m:developers/m:developer/m:name", "m:scm/m:url", "m:scm/m:connection",
    )
    missing = [path for path in required if not element_text(root, path)]
    if missing:
        raise SystemExit(f"{pom} is missing required Maven Central metadata: {missing}")
    if element_text(root, "m:version") != release_version:
        raise SystemExit(f"{pom} does not use release version {release_version}")
    for dependency in root.findall(".//m:dependency", POM_NAMESPACE):
        version = element_text(dependency, "m:version")
        if version == "unspecified" or "SNAPSHOT" in version.upper():
            group = element_text(dependency, "m:groupId")
            artifact = element_text(dependency, "m:artifactId")
            raise SystemExit(f"{pom} contains an unpublishable dependency: {group}:{artifact}:{version}")


def validate_companions(pom: Path) -> None:
    root = ElementTree.parse(pom).getroot()
    packaging = element_text(root, "m:packaging") or "jar"
    if packaging == "pom":
        return
    primary = pom.parent / f"{pom.stem}.{packaging}"
    if not primary.is_file():
        raise SystemExit(f"{pom} declares a missing primary artifact: {primary.name}")
    for classifier in ("sources", "javadoc"):
        companion = pom.parent / f"{pom.stem}-{classifier}.jar"
        if not companion.is_file():
            raise SystemExit(f"{primary} requires {companion.name} for Maven Central")


def validate_targets(group_root: Path, version: str) -> None:
    artifact = "kmedia-notification"
    targets = (
        "android", "jvm", "js", "wasm-js", "linuxx64", "linuxarm64", "mingwx64",
        "macosx64", "macosarm64", "iosarm64", "iosx64", "iossimulatorarm64",
    )
    for module in (artifact, *(f"{artifact}-{target}" for target in targets)):
        pom = group_root / module / version / f"{module}-{version}.pom"
        if not pom.is_file():
            raise SystemExit(f"Missing publication: {module}")
        if not pom.with_suffix(".module").is_file():
            raise SystemExit(f"Missing Gradle module metadata: {module}")

    for metadata in group_root.glob(f"*/{version}/*.module"):
        data = json.loads(metadata.read_text())
        if "SNAPSHOT" in metadata.read_text().upper():
            raise SystemExit(f"Snapshot dependency in {metadata}")
        for variant in data["variants"]:
            location = variant.get("available-at")
            if location:
                referenced = group_root / location["module"] / location["version"]
                if not referenced.is_dir():
                    raise SystemExit(f"Missing target referenced by {metadata}: {referenced}")

    native_files = (
        "linux-x86_64/libkmedia_notification.so",
        "linux-aarch64/libkmedia_notification.so",
        "windows-x86_64/kmedia_notification.dll",
        "macos-x86_64/libkmedia_notification.dylib",
        "macos-aarch64/libkmedia_notification.dylib",
    )
    jar = group_root / f"{artifact}-jvm" / version / f"{artifact}-jvm-{version}.jar"
    with ZipFile(jar) as archive:
        for native in native_files:
            entry = f"native/{native}"
            if entry not in archive.namelist() or not archive.getinfo(entry).file_size:
                raise SystemExit(f"Missing or empty JVM native bridge: {entry}")
    print("Verified all 13 publications and 5 JVM native bridges")


def main() -> None:
    parser = argparse.ArgumentParser(description="Prepare one kmedianotification Maven Central bundle tree.")
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--staging", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--group", default="dev.brahmkshatriya.kmedianotification")
    parser.add_argument("--require-all-targets", action="store_true")
    args = parser.parse_args()

    repository = args.repository.expanduser().resolve()
    staging = args.staging.expanduser().resolve()
    group_root = repository / Path(*args.group.split("."))
    if not group_root.is_dir():
        raise SystemExit(f"Maven group does not exist: {group_root}")
    if staging == repository or repository in staging.parents or staging in repository.parents:
        raise SystemExit("Staging and repository must not contain one another")
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)

    copied_files = 0
    copied_modules = 0
    for version_directory in sorted(group_root.glob(f"*/{args.version}")):
        destination = staging / version_directory.relative_to(repository)
        destination.mkdir(parents=True, exist_ok=True)
        module_files = 0
        for source in sorted(version_directory.iterdir()):
            if not source.is_file() or source.name.startswith("maven-metadata") or source.name.endswith(IGNORED_SUFFIXES):
                continue
            shutil.copy2(source, destination / source.name)
            copied_files += 1
            module_files += 1
        if module_files:
            copied_modules += 1

    if not copied_files:
        raise SystemExit(f"No {args.group}:{args.version} artifacts were found")
    poms = sorted(staging.rglob("*.pom"))
    if not poms:
        raise SystemExit("The staged repository contains no POM files")
    for version_directory in sorted(staging.glob(f"**/{args.version}")):
        if version_directory.is_dir() and not list(version_directory.glob("*.pom")):
            raise SystemExit(f"{version_directory} contains artifacts but no POM")
    for pom in poms:
        validate_pom(pom, args.version)
        validate_companions(pom)
    if args.require_all_targets:
        validate_targets(staging / Path(*args.group.split(".")), args.version)
    print(f"Staged {copied_files} files from {copied_modules} modules; validated {len(poms)} POMs")


if __name__ == "__main__":
    main()
