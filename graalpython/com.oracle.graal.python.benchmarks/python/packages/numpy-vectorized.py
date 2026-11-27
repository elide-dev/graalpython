# numpy on large arrays: element-wise ufuncs, reductions, percentile, cumsum, sort, where, an FFT
# and a small linear solve. Almost all the time is spent in numpy's compiled loops, so this row
# shows the cost of the C API boundary when the calls are few and large. numpy is built without an
# external BLAS (see .github/elide/README.md), so the linear algebra stays small.
#
# Run with harness.py by .github/elide/codspeed-bench, see .github/elide/README.md. The argument is
# the length of the arrays.
import math
import os

for _var in ("OMP_NUM_THREADS", "OPENBLAS_NUM_THREADS", "MKL_NUM_THREADS"):
    os.environ.setdefault(_var, "1")

import numpy as np

# length of the arrays -> result of one iteration
EXPECTED = {
    4000000: (
        2830945.870096583,
        0.8959134376782963,
        2830945.8700965187,
        0.7495966193253321,
        0.6603638159207382,
        3667766,
        1.731254587881972,
        27298.11534598013,
        0.06671724290320782,
    ),
}
# Vectorized math functions and linear algebra round differently on different CPUs.
REL_TOL = 1e-7

X = S = B = None
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


def __setup__(size=4000000):
    global X, S, B
    rs = np.random.RandomState(2)
    X = rs.rand(size)
    m = rs.rand(120, 120)
    S = m @ m.T + 120 * np.eye(120)
    B = rs.rand(120, 4)


def measure(size):
    y = np.exp(-X) * np.sin(3 * X) + np.sqrt(X) * np.log1p(X)
    total = float(y.sum())
    p90 = float(np.percentile(y, 90))
    running = float(np.cumsum(y)[-1])
    ordered = np.sort(y)
    kth = float(ordered[size // 3])
    above = float(np.where(y > 0.5, y, 0).mean())
    count = int(np.count_nonzero(y > 0.25))
    z = (X - X.mean()) / X.std()
    spread = float(np.abs(z).max())
    spectrum = np.fft.rfft(X[:1 << (size.bit_length() - 2)])
    low = float(np.abs(spectrum[1:100]).sum())
    solved = float(np.linalg.solve(S, B).sum())
    result = (total, p90, running, kth, above, count, spread, low, solved)
    _check(size, result)
    return result


def __benchmark__(size=4000000):
    return measure(size)
