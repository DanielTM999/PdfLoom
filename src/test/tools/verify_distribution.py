"""Inspect built JAR contents without loading test dependencies (stdlib only)."""
import json
import hashlib
from pathlib import Path
import sys
import zipfile

root = Path(__file__).resolve().parents[3]
def project_version():
    import xml.etree.ElementTree as tree
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    return tree.parse(root / "pom.xml").getroot().find("m:version", namespace).text.strip()


version = sys.argv[1] if len(sys.argv) > 1 and sys.argv[1] else project_version()
build_directory = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("target")
inventory = json.loads((root / "DEPENDENCIES.json").read_text(encoding="utf8"))
main_classes = {path.relative_to(root / "src/main/java").with_suffix("").as_posix() for path in (root / "src/main/java").rglob("*.java")}
main_resources = {path.relative_to(root / "src/main/resources").as_posix() for path in (root / "src/main/resources").rglob("*") if path.is_file()}
for suffix in ("", "-all"):
    path = root / build_directory / f"pdfloom-{version}{suffix}.jar"
    with zipfile.ZipFile(path) as jar:
        files = set(jar.namelist())
        for required in ("dtm/pdfloom/PdfConverter.class", "META-INF/LICENSE", "META-INF/NOTICE", "dtm/pdfloom/fonts/Roboto-Regular.ttf", "dtm/pdfloom/fonts/LICENSE.txt", "dtm/pdfloom/fonts/NOTICE.txt", "META-INF/pdfloom/DEPENDENCIES.json"):
            assert required in files, (path, required)
        for entry in inventory:
            for notice in entry["notices"]:
                assert notice in files, (path, notice)
        font_root = "dtm/pdfloom/fonts/liberation/"
        font_manifest = json.loads(jar.read(font_root + "manifest.json"))
        assert font_manifest["license"] == "OFL-1.1"
        assert font_manifest["version"] == "2.1.5"
        assert not font_manifest["modified"]
        for name, expected in font_manifest["files"].items():
            assert hashlib.sha256(jar.read(font_root + name)).hexdigest() == expected, (path, name)
        assert len([name for name in font_manifest["files"] if name.endswith(".ttf")]) == 12
        assert not any(f.startswith(("org/thymeleaf/", "com/google/zxing/", "org/junit/", "org/springframework/", "com/openhtmltopdf/", "org/xhtmlrenderer/", "com/itextpdf/", "dtm/pdfloom/examples/")) for f in files)
        for entry in files:
            if entry.startswith("dtm/pdfloom/") and not entry.endswith("/"):
                if entry.endswith(".class"):
                    assert entry.split("$")[0].removesuffix(".class") in main_classes, (path, entry)
                else:
                    assert entry in main_resources, (path, entry)
        if suffix:
            for required in ("org/jsoup/Jsoup.class", "com/helger/css/reader/CSSReader.class", "org/apache/pdfbox/pdmodel/PDDocument.class", "org/apache/batik/transcoder/image/PNGTranscoder.class"):
                assert required in files, required
        print(f"Verified {path.name}: {path.stat().st_size:,} bytes; resources and notices retained.")
