# pandas: merges of a fact table with two dimension tables, groupby with named aggregations,
# nlargest, pivot_table and a grouped rank.
#
# Run with harness.py by .github/elide/codspeed-bench, see .github/elide/README.md. The argument is
# the number of rows of the fact table.
import math
import os

for _var in ("OMP_NUM_THREADS", "OPENBLAS_NUM_THREADS", "MKL_NUM_THREADS"):
    os.environ.setdefault(_var, "1")

import numpy as np
import pandas as pd

# number of rows -> result of one iteration
EXPECTED = {
    600000: (
        524828,
        32,
        130964960.46000001,
        3217.1785678239376,
        524828,
        1750154.6600000001,
        52768190.0,
        136105,
        1762282.68,
    ),
}
REL_TOL = 1e-9

SALES = CUSTOMERS = PRODUCTS = None
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


def __setup__(size=600000):
    global SALES, CUSTOMERS, PRODUCTS
    rs = np.random.RandomState(3)
    SALES = pd.DataFrame({
        "order_id": np.arange(size),
        "cust_id": rs.randint(0, 5000, size),
        "prod_id": rs.randint(0, 800, size),
        "qty": rs.randint(1, 10, size),
        "price": rs.randint(1, 10000, size) / 100,
        "day": rs.randint(0, 365, size),
    })
    CUSTOMERS = pd.DataFrame({"cust_id": np.arange(5000), "region": rs.choice(["EU", "US", "APAC", "LATAM"], 5000),
                              "segment": rs.choice(["retail", "smb", "ent"], 5000)})
    PRODUCTS = pd.DataFrame({"prod_id": np.arange(700), "category": rs.choice(list("ABCDEFGH"), 700),
                             "cost": rs.randint(1, 6000, 700) / 100})


def measure(size):
    df = SALES.merge(CUSTOMERS, on="cust_id", how="left").merge(PRODUCTS, on="prod_id", how="inner")
    df["revenue"] = df["qty"] * df["price"]
    df["margin"] = df["revenue"] - df["qty"] * df["cost"]
    g = df.groupby(["region", "category"]).agg(rev=("revenue", "sum"), m=("margin", "mean"), n=("order_id", "count"))
    top = df.groupby("cust_id")["revenue"].sum().nlargest(50)
    pivot = df.pivot_table(index="segment", columns="category", values="margin", aggfunc="sum")
    rank = df.groupby("region")["revenue"].rank(method="first")
    monthly = df.assign(month=df["day"] // 30).groupby("month")["revenue"].sum()
    result = (len(df), len(g), float(g["rev"].sum()), float(g["m"].sum()), int(g["n"].sum()), float(top.sum()),
              float(pivot.values.sum()), int(rank.max()), float(monthly.iloc[-1]))
    _check(size, result)
    return result


def __benchmark__(size=600000):
    return measure(size)
