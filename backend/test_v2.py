"""Run native-library suites in isolated processes (Sherpa and Torch runtimes)."""
import subprocess
import sys
from pathlib import Path

if __name__=='__main__':
    for path in sorted(Path(__file__).parent.glob('test_v2_*.py')):
        print(path.name,flush=True)
        subprocess.run([sys.executable,str(path)],check=True)
