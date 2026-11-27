# pandas with Python functions per row or element: DataFrame.apply(axis=1), Series.apply and
# Series.map with lambdas, iterrows and itertuples.
#
# Run with harness.py by .github/elide/codspeed-bench, see .github/elide/README.md. The argument is
# the number of rows.
import math
import os

for _var in ("OMP_NUM_THREADS", "OPENBLAS_NUM_THREADS", "MKL_NUM_THREADS"):
    os.environ.setdefault(_var, "1")

import numpy as np
import pandas as pd

# number of rows -> result of one iteration
EXPECTED = {
    20000: (2823021.5738181015, 9988.025200000064, 354795085.89, 88890, 3493, 1905),
}
REL_TOL = 1e-9

DF = None
_first = []


def _check(size, result):
    """Fails the benchmark on a wrong result. Floats differ in the last digits between platforms."""
    expected = EXPECTED.get(size)
    if expected is None:
        if not _first:
            _first.append(result)
            print("### no expected result for size %d, the first iteration gave %r" % (size, result))
        expected = _first[0]
    if len(result) != len(expected) or not all(
            math.isclose(r, e, rel_tol=REL_TOL) if isinstance(e, float) else r == e
            for r, e in zip(result, expected)):
        raise AssertionError("wrong result for size %d: %r, expected %r" % (size, result, expected))


def classify(row):
    if row["age"] < 25:
        return "young" if row["income"] < 30000 else "young-rich"
    if row["score"] > 0.8:
        return "vip"
    return "regular"


def __setup__(size=20000):
    global DF
    rs = np.random.RandomState(5)
    DF = pd.DataFrame({"age": rs.randint(18, 80, size), "income": rs.randint(10000, 150000, size),
                       "score": rs.rand(size).round(4), "name": ["user_%d" % i for i in range(size)]})


def measure(size):
    df = DF.copy()
    df["cls"] = df.apply(classify, axis=1)
    df["tax"] = df["income"].apply(lambda x: x * 0.15 if x < 50000 else x * 0.23)
    df["nlen"] = df["name"].map(lambda s: len(s.split("_")[1]))
    per_age = 0.0
    for _, row in df.iloc[:size // 3].iterrows():
        per_age += row["tax"] / (row["age"] + 1)
    score = 0.0
    for t in df.itertuples():
        score += t.score
    counts = df["cls"].value_counts()
    result = (per_age, score, float(df["tax"].sum()), int(df["nlen"].sum()), int(counts["vip"]),
              int(counts["young-rich"]))
    _check(size, result)
    return result


def __benchmark__(size=20000):
    return measure(size)
