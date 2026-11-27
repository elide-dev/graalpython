# pydantic: a JSON array of invoices (nested models, a discriminated union, constrained fields) is
# validated into models with validate_json and serialized back with dump_json, and part of it goes
# through model_dump_json and model_validate_json one model at a time.
#
# Run with harness.py by .github/elide/codspeed-bench, see .github/elide/README.md. The argument is
# the number of invoices.
import math
import random
from datetime import date
from typing import Annotated, Literal, Union

from pydantic import BaseModel, Field, TypeAdapter

# number of invoices -> result of one iteration
EXPECTED = {
    8000: (3626504, 35782, 2633, 1832408),
}
REL_TOL = 1e-9


class Card(BaseModel):
    kind: Literal["card"]
    last4: str = Field(pattern=r"^\d{4}$")
    brand: str


class Bank(BaseModel):
    kind: Literal["bank"]
    iban: str
    bic: str


class Wallet(BaseModel):
    kind: Literal["wallet"]
    provider: str
    token: str


Payment = Annotated[Union[Card, Bank, Wallet], Field(discriminator="kind")]


class Item(BaseModel):
    sku: str
    qty: int = Field(ge=1)
    unit_price: float
    tags: list[str] = []


class Invoice(BaseModel):
    id: int
    customer: str
    issued: date
    paid: bool
    payment: Payment
    items: list[Item]
    meta: dict[str, str] = {}


ADAPTER = TypeAdapter(list[Invoice])
PAYLOAD = b""
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


def __setup__(size=8000):
    global PAYLOAD
    r = random.Random(7)
    raw = []
    for i in range(size):
        kind = r.choice(["card", "bank", "wallet"])
        if kind == "card":
            payment = {"kind": "card", "last4": "%04d" % r.randint(0, 9999), "brand": r.choice(["visa", "mc"])}
        elif kind == "bank":
            payment = {"kind": "bank", "iban": "CZ%d" % r.randint(10**9, 10**10), "bic": "GIBACZPX"}
        else:
            payment = {"kind": "wallet", "provider": "pp", "token": "tok%d" % i}
        raw.append({
            "id": i, "customer": "cust-%d" % r.randint(0, 99),
            "issued": "2024-%02d-%02d" % (r.randint(1, 12), r.randint(1, 28)),
            "paid": r.random() < 0.5, "payment": payment,
            "items": [{"sku": "SKU%d" % r.randint(0, 999), "qty": r.randint(1, 5),
                       "unit_price": round(r.random() * 100, 2),
                       "tags": ["t%d" % r.randint(0, 9) for _ in range(2)]}
                      for _ in range(r.randint(1, 8))],
            "meta": {"src": "web", "region": r.choice(["eu", "us"])},
        })
    PAYLOAD = ADAPTER.dump_json(ADAPTER.validate_python(raw))


def measure(size):
    invoices = ADAPTER.validate_json(PAYLOAD)
    out = ADAPTER.dump_json(invoices)
    items = sum(len(inv.items) for inv in invoices)
    cards = sum(1 for inv in invoices if inv.payment.kind == "card")
    single = 0
    for inv in invoices[:size // 2]:
        text = inv.model_dump_json()
        single += len(text) + len(Invoice.model_validate_json(text).items)
    result = (len(out), items, cards, single)
    _check(size, result)
    return result


def __benchmark__(size=8000):
    return measure(size)
