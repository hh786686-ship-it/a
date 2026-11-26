"""Download and verify CrispASR Android ARM64 native library for the APK."""
import hashlib
import os
import subprocess
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
        sdk = pathlib.Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
        ndks = sorted((sdk / "ndk").glob("*"), reverse=True)
        candidates = []
        for ndk in ndks:
            candidates.extend(ndk.glob("toolchains/llvm/prebuilt/linux-x86_64/lib/clang/*/lib/linux/aarch64/libomp.so"))
            candidates.extend(ndk.glob("toolchains/llvm/prebuilt/linux-x86_64/lib/clang/*/lib/linux/libomp.so"))
        def arm64(path):
            with path.open("rb") as f:
                header = f.read(20)
            return header[:4] == b"\x7fELF" and header[4] == 2 and int.from_bytes(header[18:20], "little") == 183
        if not (DEST / "libomp.so").is_file():
            source = next((x for x in candidates if arm64(x)), None)
            if source is None:
                raise RuntimeError("No ARM64 OpenMP runtime in installed Android NDK")
            shutil.copyfile(source, DEST / "libomp.so")
            print("Bundled OpenMP runtime:", source)
        assert arm64(DEST / "libomp.so")
        print("Bundled", count, "engine libraries plus OpenMP runtime")
if __name__ == "__main__":
    main()
