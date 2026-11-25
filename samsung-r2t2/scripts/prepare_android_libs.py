"""Download and verify CrispASR Android ARM64 native library for the APK."""
import hashlib
import pathlib
import shutil
import tarfile
import tempfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEST = ROOT / "android" / "app" / "src" / "main" / "jniLibs" / "arm64-v8a"
URL = "https://github.com/CrispStrobe/CrispASR/releases/download/v0.8.55/crispasr-android-arm64-v8a.tar.gz"
SHA256 = "1070d679c34597c7bfbf6c710933379f22f742e4c393d66ab4bfa06e90ad3bad"

def main():
    DEST.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as temporary:
        archive = pathlib.Path(temporary) / "crispasr-android.tar.gz"
        urllib.request.urlretrieve(URL, archive)
        with archive.open("rb") as f:
            digest = hashlib.file_digest(f, "sha256").hexdigest()
        if digest != SHA256:
            raise RuntimeError("Native library SHA256 verification failed: " + digest)
        count = 0
        with tarfile.open(archive, "r:gz") as tar:
            for member in tar:
                name = pathlib.PurePosixPath(member.name).name
                if member.isfile() and name.endswith(".so"):
                    source = tar.extractfile(member)
                    if source:
                        with (DEST / name).open("wb") as output:
                            shutil.copyfileobj(source, output)
                        count += 1
        if not (DEST / "libcrispasr.so").is_file():
            raise RuntimeError("libcrispasr.so missing from engine archive")
        print("Bundled", count, "native libraries")
if __name__ == "__main__":
    main()
