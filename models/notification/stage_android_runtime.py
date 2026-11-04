"""Stage checksum-verified public Maven artifacts for repeatable offline Android tests."""
import hashlib
from pathlib import Path
import urllib.request

BASE = "https://repo.maven.apache.org/maven2/"
COORDINATE = "com/microsoft/onnxruntime/onnxruntime-android/1.23.2/"
TARGET = Path(__file__).resolve().parent / ".maven" / COORDINATE

if __name__ == "__main__":
    TARGET.mkdir(parents=True, exist_ok=True)
    for extension in ("pom", "aar"):
        name = "onnxruntime-android-1.23.2." + extension
        with urllib.request.urlopen(BASE + COORDINATE + name + ".sha1", timeout=60) as response:
            expected = response.read().decode().strip().split()[0]
        path = TARGET / name
        if not path.exists() or hashlib.sha1(path.read_bytes()).hexdigest() != expected:
            temporary = path.with_suffix(path.suffix + ".tmp")
            with urllib.request.urlopen(BASE + COORDINATE + name, timeout=60) as response, temporary.open("wb") as output:
                while chunk := response.read(1024 * 1024):
                    output.write(chunk)
            if hashlib.sha1(temporary.read_bytes()).hexdigest() != expected:
                raise ValueError("Maven checksum mismatch: " + name)
            temporary.replace(path)
        print(name, path.stat().st_size, "Maven checksum verified; SHA256=" + hashlib.sha256(path.read_bytes()).hexdigest(), flush=True)
