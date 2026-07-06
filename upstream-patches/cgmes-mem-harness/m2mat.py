"""Convert a MATPOWER .m case file to a MAT v5 .mat file with an 'mpc' struct."""
import re
import sys

import numpy as np
import scipy.io


def parse_matrix(text, name):
    m = re.search(r"mpc\.%s\s*=\s*\[(.*?)\];" % re.escape(name), text, re.S)
    if not m:
        return None
    rows = []
    for line in m.group(1).strip().splitlines():
        line = line.split("%")[0].strip().rstrip(";")
        if not line:
            continue
        rows.append([float(x) for x in line.split()])
    return np.array(rows)


def main(src, dst):
    text = open(src).read()
    base_mva = float(re.search(r"mpc\.baseMVA\s*=\s*([0-9.eE+-]+)\s*;", text).group(1))
    mpc = {
        "version": "2",
        "baseMVA": base_mva,
        "bus": parse_matrix(text, "bus"),
        "gen": parse_matrix(text, "gen"),
        "branch": parse_matrix(text, "branch"),
    }
    for k in ("bus", "gen", "branch"):
        if mpc[k] is None:
            raise SystemExit("missing mpc." + k)
        print(f"{k}: {mpc[k].shape}")
    scipy.io.savemat(dst, {"mpc": mpc}, format="5", oned_as="column")
    print("wrote", dst)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
