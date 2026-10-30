"""Package an explicit allowlist; never include build caches, signing material or local SDK paths."""
from pathlib import Path
import hashlib
import re
import zipfile

root = Path(__file__).resolve().parent.parent
version = re.search(r'versionName = "([^"]+)"', (root / "app/build.gradle.kts").read_text(encoding="utf-8"))[1]
files = [root / name for name in (
    "README.md", "LICENSE", "THIRD_PARTY_NOTICES.md", ".gitignore", ".gitattributes", "settings.gradle.kts", "build.gradle.kts",
    "gradle.properties", "gradlew", "gradlew.bat", "app/build.gradle.kts",
    "verification/验证结果.md", "verification/verification.json",
    "verification/apk-signature.txt", "verification/apk-metadata.txt", "verification/apk-manifest.txt",
    "verification/device-normal.xml", "verification/device-small-largefont.xml",
    "verification/scroll-gfxinfo.txt", "verification/scroll-baseline-1.1.1.txt", "verification/release-home.png", "verification/upgrade.json",
)]
for directory in ("app/src", "gradle", "scripts"):
    files.extend(p for p in (root / directory).rglob("*") if p.is_file() and "__pycache__" not in p.parts)
for directory in ("verification/normal", "verification/small-largefont"):
    files.extend((root / directory).glob("*.png"))
destination = root / f"dist/TrafficBoard-{version}-source.zip"
assert (root / "verification/验证结果.md").is_file(), "Run verify_release.py first"
with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(set(files)):
        if path.is_file():
            archive.write(path, path.relative_to(root).as_posix())
with zipfile.ZipFile(destination) as archive:
    assert archive.testzip() is None
    names = archive.namelist()
    assert "app/src/main/AndroidManifest.xml" in names
    assert "gradle/wrapper/gradle-wrapper.jar" in names
    assert not any(n.endswith((".jks", ".keystore")) or "signing.properties" in n or n.endswith("local.properties") for n in names)
checksum = hashlib.sha256(destination.read_bytes()).hexdigest().upper()
with (root / "dist/SHA256SUMS.txt").open("w", encoding="ascii") as output:
    apk = root / f"dist/TrafficBoard-{version}.apk"
    output.write(f"{hashlib.sha256(apk.read_bytes()).hexdigest().upper()}  {apk.name}\n")
    output.write(f"{checksum}  {destination.name}\n")
print(f"Source ZIP: {destination}\nFiles: {len(names)}\nSHA256: {checksum}")
