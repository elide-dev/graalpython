# lxml: parse of a namespaced XML document, XPath queries (attributes, sums, predicates, a compiled
# XPath with variables), iterfind with edits, serialization and parse of the result, and lxml.html
# parsing, link extraction and serialization of an HTML table.
#
# Run with harness.py by .github/elide/codspeed-bench, see .github/elide/README.md. The argument is
# the number of items in the XML document. One iteration does the work ROUNDS times on data of that
# size, so that less data is alive at a time.
import math
import random

from lxml import etree, html

# number of items -> result of one round
EXPECTED = {
    2500: (264, 307224.9399999998, 766, 2500, 418417, 2500, 1250, 60754, 16278, 114026),
}
ROUNDS = 4
REL_TOL = 1e-9

NS = "urn:example:inventory"
XML_DOC = b""
HTML_DOC = ""
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


def __setup__(size=2500):
    global XML_DOC, HTML_DOC
    r = random.Random(61)
    root = etree.Element("{%s}inventory" % NS, nsmap={"inv": NS})
    for i in range(size):
        item = etree.SubElement(root, "{%s}item" % NS, sku="S%05d" % i, cat=r.choice(["a", "b", "c", "d"]))
        etree.SubElement(item, "{%s}name" % NS).text = "Item %d <&>" % i
        etree.SubElement(item, "{%s}qty" % NS).text = str(r.randint(0, 500))
        etree.SubElement(item, "{%s}price" % NS).text = "%.2f" % r.uniform(1, 999)
        if r.random() < 0.3:
            etree.SubElement(item, "{%s}note" % NS).text = "fragile"
    XML_DOC = etree.tostring(root, xml_declaration=True, encoding="utf-8")
    rows = "".join('<tr><td><a href="/p/%d">Product %d</a></td><td class="p">%d</td></tr>' % (i, i, r.randint(1, 99))
                   for i in range(size // 2))
    HTML_DOC = ("<html><head><title>T</title></head><body><div id=main><table>%s</table>"
                "<p>Text <b>bold</b><br>tail</div></body></html>" % rows)


def one_round(size):
    ns = {"i": NS}
    root = etree.fromstring(XML_DOC)
    low = root.xpath("//i:item[number(i:qty) < 50]/@sku", namespaces=ns)
    total = root.xpath("sum(//i:item[@cat='a']/i:price)", namespaces=ns)
    fragile = len(root.xpath("//i:item[i:note='fragile']", namespaces=ns))
    names_of = etree.XPath("i:item[@cat=$c]/i:name/text()", namespaces=ns)
    names = sum(len(names_of(root, c=c)) for c in "abcd")
    for item in root.iterfind("i:item", ns):
        qty = item.find("i:qty", ns)
        qty.text = str(int(qty.text) + 1)
    out = etree.tostring(root, pretty_print=True)
    count = int(etree.fromstring(out).xpath("count(//i:item)", namespaces=ns))
    doc = html.fromstring(HTML_DOC)
    links = [a.get("href") for a in doc.xpath("//a")]
    prices = sum(int(td.text_content()) for td in doc.xpath("//td[@class='p']"))
    doc.make_links_absolute("https://shop.example/")
    text = doc.text_content()
    serialized = html.tostring(doc)
    result = (len(low), float(total), fragile, names, len(out), count, len(links), prices, len(text), len(serialized))
    _check(size, result)
    return result


def measure(size):
    for _ in range(ROUNDS):
        result = one_round(size)
    return result


def __benchmark__(size=2500):
    return measure(size)
