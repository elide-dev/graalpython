# pandas: text cleaning with the Series.str methods (strip, lower, contains, extract, split,
# replace, title, len, slice) and parsing of date strings with to_datetime.
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
    60000: (20146, 569021, 2878839, 2022984, 9922, 180777, 2620, 24, 938675),
}
REL_TOL = 1e-9

TEXT = None
DATES = None
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


def __setup__(size=60000):
    global TEXT, DATES
    rs = np.random.RandomState(6)
    first = rs.choice(["  John", "jane ", "ALICE", "Bob", "Éva", "Zoë "], size)
    domain = rs.choice(["example.com", "mail.cz", "corp.org"], size)
    TEXT = pd.Series(["%s Doe <%s.%d@%s> #%d" % (f, f.strip().lower(), i, d, i % 97)
                      for i, (f, d) in enumerate(zip(first, domain))])
    base = pd.Timestamp("2022-01-01")
    seconds = rs.randint(0, 86400 * 700, size)
    DATES = pd.Series([(base + pd.Timedelta(seconds=int(s))).strftime("%Y-%m-%d %H:%M:%S") for s in seconds])


def measure(size):
    s = TEXT.str.strip().str.lower()
    corp = s.str.contains("corp.org", regex=False)
    emails = s.str.extract(r"<([^>]+)>", expand=False)
    user = emails.str.split("@").str[0]
    tag = s.str.extract(r"#(\d+)$", expand=False).astype(int)
    cleaned = s.str.replace(r"\s+", " ", regex=True).str.title()
    d = pd.to_datetime(DATES, format="%Y-%m-%d %H:%M:%S")
    by_month = d.dt.to_period("M").value_counts().sort_index()
    days = pd.to_datetime(DATES.str.slice(0, 10)).dt.day
    result = (int(corp.sum()), int(user.str.len().sum()), int(tag.sum()), int(cleaned.str.len().sum()),
              int(cleaned.str.startswith("Éva").sum()), int(d.dt.dayofweek.sum()), int(by_month.iloc[0]),
              len(by_month), int(days.sum()))
    _check(size, result)
    return result


def __benchmark__(size=60000):
    return measure(size)
