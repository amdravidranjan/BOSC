"""Build bosc_kaggle.ipynb from bosc_kaggle.py (cells split on '# %%').

'# @@INLINE <repo path>' is replaced by code that writes that file next to the notebook and puts it first
on sys.path, so the notebook is self-contained (it does not depend on the GitHub copy being up to date).

  python backend/kaggle/build_notebook.py
"""
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
src = (HERE / "bosc_kaggle.py").read_text(encoding="utf-8")

out_lines = []
for line in src.splitlines():
    if line.strip().startswith("# @@INLINE "):
        rel = line.strip().split(" ", 2)[2]
        body = (ROOT / rel).read_text(encoding="utf-8")
        assert "'''" not in body, f"{rel} contains ''' and cannot be inlined"
        name = Path(rel).name
        out_lines += [f"# ---- inlined copy of {rel} (single source of truth lives in the repo)",
                      f"_INLINE_SRC = r'''{body}'''",
                      f"(WORK / {name!r}).write_text(_INLINE_SRC, encoding='utf-8')",
                      "sys.path.insert(0, str(WORK))",
                      f"sys.modules.pop({Path(rel).stem!r}, None)"]
    else:
        out_lines.append(line)

cells, cur, kind = [], [], "code"
for line in out_lines + ["# %%"]:
    if line.startswith("# %%"):
        if any(x.strip() for x in cur):
            while cur and not cur[-1].strip():
                cur.pop()
            if kind == "markdown":
                text = [x[2:] if x.startswith("# ") else x.lstrip("#") for x in cur]
                cells.append({"cell_type": "markdown", "metadata": {}, "source": [t + "\n" for t in text]})
            else:
                cells.append({"cell_type": "code", "metadata": {}, "execution_count": None, "outputs": [],
                              "source": [t + "\n" for t in cur]})
            cells[-1]["source"][-1] = cells[-1]["source"][-1].rstrip("\n")
        cur, kind = [], ("markdown" if "[markdown]" in line else "code")
    else:
        cur.append(line)

nb = {"cells": cells, "metadata": {
    "kernelspec": {"display_name": "Python 3", "language": "python", "name": "python3"},
    "language_info": {"name": "python"},
    "kaggle": {"accelerator": "gpu", "isInternetEnabled": True, "isGpuEnabled": True}},
    "nbformat": 4, "nbformat_minor": 5}
(HERE / "bosc_kaggle.ipynb").write_text(json.dumps(nb, indent=1, ensure_ascii=False), encoding="utf-8")
print(f"wrote bosc_kaggle.ipynb: {len(cells)} cells")
