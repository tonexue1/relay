from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common


if __name__ == "__main__":
    path = common.download_gguf()
    print(path)
