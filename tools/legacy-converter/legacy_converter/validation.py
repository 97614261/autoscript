"""Optional bundled read-only Rust compiler gate. No shell, scripts, or imported DEX."""
import json
import os
import pathlib
import subprocess
import tempfile
import time


def bundled_validator():
    path = pathlib.Path(__file__).resolve().parents[1] / "bin" / "validate_project.exe"
    return path if path.is_file() else None


def compiler_gate(executable):
    executable = pathlib.Path(executable).resolve(strict=True)

    def validate(project, job):
        # File-backed bounded read avoids accumulating arbitrary child stdout in RAM.
        with tempfile.TemporaryFile() as stream:
            process = subprocess.Popen([str(executable), str(project)], stdout=stream, stderr=stream,
                                       creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            deadline = time.monotonic() + 30
            try:
                while process.poll() is None:
                    job.check()
                    if time.monotonic() >= deadline or os.fstat(stream.fileno()).st_size > 65536:
                        raise TimeoutError("compiler gate exceeded budget")
                    time.sleep(0.05)
                stream.seek(0)
                data = stream.read(65537)
                if len(data) > 65536 or process.returncode not in (0, 2):
                    raise ValueError("compiler gate failed")
                result = json.loads(data.decode("utf-8"))
                if (not isinstance(result, dict) or type(result.get("compiled")) is not bool
                        or result.get("manifestValid") is not True
                        or type(result.get("structuralErrors")) is not int):
                    raise ValueError("compiler gate response invalid")
                if (result["compiled"] != (process.returncode == 0) or result["structuralErrors"] < 0
                        or result["compiled"] and result["structuralErrors"] != 0):
                    raise ValueError("compiler gate response inconsistent")
                return result
            finally:
                if process.poll() is None:
                    process.kill()
                process.wait(timeout=5)
    return validate
