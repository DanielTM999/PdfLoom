"""Verify the reviewed runtime dependency inventory and retained notices (stdlib only).

First run Maven dependency:build-classpath with -DincludeScope=runtime and
-Dmdep.outputFile=target/runtime-classpath.txt. --refresh-notices is explicit and
only refreshes versions/notices for artifacts already reviewed in DEPENDENCIES.json.
New artifacts always require a human license review and an inventory entry.
"""
import argparse
import json
import os
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser()
parser.add_argument("--refresh-notices", action="store_true")
args = parser.parse_args()
inventory = json.loads((root / "DEPENDENCIES.json").read_text(encoding="utf8"))
entries = {entry["artifactId"]: entry for entry in inventory}
paths = (root / "target/runtime-classpath.txt").read_text(encoding="utf8").strip().split(os.pathsep)
seen = set()
resource_root = root / "src/main/resources"
for name in paths:
    path = Path(name)
    artifact, version = path.parent.parent.name, path.parent.name
    if artifact not in entries:
        raise SystemExit(f"Unreviewed runtime dependency: {path}")
    entry = entries[artifact]
    coordinate_suffix = f"{entry['groupId'].replace('.', '/')}/{artifact}/{version}/{path.name}"
    if not path.as_posix().endswith(coordinate_suffix):
        raise SystemExit(f"Runtime group does not match reviewed coordinate: {path}")
    if entry["license"] not in {"Apache-2.0", "MIT", "Apache-2.0 AND W3C", "Apache-2.0 AND W3C AND SAX-public-domain"}:
        raise SystemExit(f"Unreviewed license: {entry}")
    if version != entry["version"]:
        if not args.refresh_notices:
            raise SystemExit(f"Version differs from reviewed inventory: {artifact}: {version}")
        entry["version"] = version
    seen.add(artifact)
    if args.refresh_notices:
        with zipfile.ZipFile(path) as jar:
            originals = [name for name in jar.namelist() if not name.endswith("/") and (
                Path(name).name.upper().startswith(("LICENSE", "NOTICE", "COPYING"))
                or name.lower().startswith(("license/", "licenses/")))]
            for original in originals:
                notice = f"META-INF/pdfloom/licenses/{artifact}/{original}"
                if notice not in entry["notices"]:
                    entry["notices"].append(notice)
            for notice in entry["notices"]:
                original = notice.split(f"licenses/{artifact}/", 1)[1]
                if original in jar.namelist():
                    destination = resource_root / notice
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    destination.write_bytes(jar.read(original))
    for notice in entry["notices"]:
        if not (resource_root / notice).is_file():
            raise SystemExit(f"Missing retained notice: {notice}")
if seen != set(entries):
    raise SystemExit(f"Inventory does not match runtime closure: {set(entries) - seen}")
if args.refresh_notices:
    contents = json.dumps(inventory, ensure_ascii=False, indent=2) + "\n"
    (root / "DEPENDENCIES.json").write_text(contents, encoding="utf8")
    (resource_root / "META-INF/pdfloom/DEPENDENCIES.json").write_text(contents, encoding="utf8")
print(f"Verified {len(seen)} runtime artifacts; all reviewed notices present.")
