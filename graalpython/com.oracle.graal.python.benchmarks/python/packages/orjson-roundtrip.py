# orjson: dumps and loads of a nested document tree (dicts, lists, strings with non-ASCII text,
# floats, None), also with OPT_INDENT_2 | OPT_SORT_KEYS, and dumps of a list of dataclasses.
#
# Run with harness.py by .github/elide/codspeed-bench, see .github/elide/README.md. The argument is
# the number of users in the document. One iteration does the work ROUNDS times on data of that
# size, so that less data is alive at a time.
import dataclasses
import math
import random

import orjson

# number of users -> result of one round
EXPECTED = {
    2500: (4290716, 10000, 1962336, 316515, 6334, 11078071.56),
}
ROUNDS = 4
REL_TOL = 1e-9


@dataclasses.dataclass
class Event:
    t: int
    kind: str
    value: int


DOC = None
EVENTS = None
BLOB = b""
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


def make_doc(n, seed):
    r = random.Random(seed)
    words = ["alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta", "Příliš", "žluťoučký", "日本"]
    users = []
    for i in range(n):
        users.append({
            "id": i, "uuid": "%032x" % r.getrandbits(128), "name": "%s %s" % (r.choice(words), r.choice(words)),
            "active": r.random() < 0.7, "balance": round(r.uniform(-1000, 10000), 2), "score": r.random(),
            "tags": [r.choice(words) for _ in range(r.randint(0, 5))],
            "address": {"street": "%d Main St" % r.randint(1, 999), "city": r.choice(words),
                        "geo": [r.uniform(-90, 90), r.uniform(-180, 180)]},
            "events": [{"t": 1700000000 + r.randint(0, 10**7), "kind": r.choice(["click", "view", "buy"]),
                        "value": r.randint(0, 100)}
                       for _ in range(r.randint(0, 6))],
            "note": None if i % 3 else "x" * r.randint(10, 80),
        })
    return {"version": 3, "generated": "2024-05-06T07:08:09Z", "users": users}


def __setup__(size=2500):
    global DOC, EVENTS, BLOB
    DOC = make_doc(size, 11)
    EVENTS = [Event(e["t"], e["kind"], e["value"]) for u in DOC["users"] for e in u["events"]]
    BLOB = orjson.dumps(DOC)


def one_round(size):
    dumped = 0
    loaded = 0
    for _ in range(4):
        dumped += len(orjson.dumps(DOC))
        loaded += len(orjson.loads(BLOB)["users"])
    pretty = len(orjson.dumps(DOC, option=orjson.OPT_INDENT_2 | orjson.OPT_SORT_KEYS))
    events = len(orjson.dumps(EVENTS))
    doc = orjson.loads(BLOB)
    tags = sum(len(u["tags"]) for u in doc["users"])
    balance = sum(u["balance"] for u in doc["users"])
    result = (dumped, loaded, pretty, events, tags, balance)
    _check(size, result)
    return result


def measure(size):
    for _ in range(ROUNDS):
        result = one_round(size)
    return result


def __benchmark__(size=2500):
    return measure(size)
