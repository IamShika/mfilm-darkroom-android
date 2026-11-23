#!/usr/bin/env python3
"""Every film stock's state, on the pages people read, from one source.

    python3 ml-dev/films/states.py            write the pages
    python3 ml-dev/films/states.py --check    are the pages what this writes? (CI)

A film stock on the shelf is in one of four states (#120):

  measured     its record in ml-dev/references says "measured: yes": colour
               and tone within the pass marks of a paired shot of the real
               film. With its numbers, when the roll was shot, and whether
               the roll was in date
  style        in the style of its film, and not measured yet. Every film
               stock that stands for a real film starts here
  impression   infrared film: a Canon with its infrared filter in place does
               not record what that film does. Not measured yet, and
               described as an impression (films.py: IMPRESSION)
  own          M-Film's own invention, which stands for no real film
               (films.py: OWN)

The state comes from the shelf (films.py, filmluts.py) and from the
references' records, so claiming a film stock as measured changes the pages
when this is run again, with no hand edit. It writes

    FILMS.md             a line under every film stock, and the count
    README.md            the count, between <!-- film-states:start --> and <!-- film-states:end -->
    README.th.md         the same in Thai
    docs/MANUAL.th.md    the same in Thai

and the website takes each film stock's state from here too
(filmos/web/sitedata.py). --check also reads those pages and the website's
texts for words that say a film stock matches its film (CLAIMS): nothing may
say so of a film stock that is not measured.

Needs numpy (through measure.py).
"""

import argparse
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "filmos/tools"))
import films  # noqa: E402
import measure  # noqa: E402

REFERENCES = HERE.parent / "references"
START, END = "<!-- film-states:start -->", "<!-- film-states:end -->"
# the pages with the count between the marks: the page, its language, its link to the paired-shot page and to FILMS.md
PAGES = [("FILMS.md", "en", "docs/paired-shot.md", None), ("README.md", "en", "docs/paired-shot.md", "FILMS.md"),
         ("README.th.md", "th", "docs/paired-shot.th.md", "FILMS.md"), ("docs/MANUAL.th.md", "th", "paired-shot.th.md", "../FILMS.md")]
# what is read for claims, besides the pages above
TEXTS = ["site/index.html", "site/install.html", "site/js/i18n.js", "ml-dev/CAMERA-README.txt", "ml-dev/filmos/tools/catalog.py",
         ".github/release-darkrooms-blurb.md"]
# words that say a film stock is its film. Checked a line at a time
CLAIMS = [r"\btrue[- ]to[- ](life|film)\b", r"\b(match(es|ing)?|reproduc(es|ing)|identical to|accurate to|faithful to|indistinguishable from|the same as) (the |a |its |real )*(real )?film\b",
          r"\b(true|accurate|faithful|authentic) (film )?(colou?rs?|emulations?|simulations?|reproductions?)\b",
          "เหมือนฟิล์มจริง", "ตรงกับฟิล์มจริง", "สีเดียวกับฟิล์ม", "เหมือนกับฟิล์มจริง"]


class State:
    """A film stock's state: kind (measured, style, impression, own) and, when measured, tone and colour (each a
    mean and a worst patch; colour None for black-and-white), shot (the day the roll was), expired (True, False,
    or None when the record's dates cannot be read) and the paired shot's name."""

    def __init__(self, key, kind, **more):
        self.key, self.kind, self.name = key, kind, films.FILMS[key]["name"]
        self.colour = self.tone = self.shot = self.expired = self.paired = None
        self.__dict__.update(more)

    def numbers(self):
        return ((f"colour {self.colour[0]:.1f}, " if self.colour else "") + f"tone {self.tone[0]:.1f}")


def _measured(record):
    """What buildlook.py wrote beside the record when it built the look: the distance on the held-back frames."""
    path = record.path.with_name("measured.txt")
    if not path.exists():
        raise measure.Bad(f"{record.path} says the film stock is measured, and there is no {path.name} beside it (films/buildlook.py writes it)")
    got = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        m = re.fullmatch(r"(colour|tone): ([\d.]+) worst ([\d.]+)", line.split("#", 1)[0].strip())
        if m:
            got[m.group(1)] = (float(m.group(2)), float(m.group(3)))
    if "tone" not in got:
        raise measure.Bad(f"{path}: no \"tone: mean worst worst\" line")
    return got


def states(references=REFERENCES):
    """Every film stock on the shelf and its state, in shelf order."""
    import gentables
    out = {}
    for key in gentables.shelf():
        out[key] = State(key, "own" if key in films.OWN else "impression" if key in films.IMPRESSION else "style")
    for path in sorted(Path(references).glob("*/record.txt")):
        record = measure.Record(path)
        if record.made_up or not record.claimed:
            continue
        key = record.fields["film stock"]
        record.film
        if out[key].kind == "measured":
            raise measure.Bad(f"two paired shots claim {key} as measured: {out[key].paired} and {record.name}")
        if out[key].kind == "own":
            raise measure.Bad(f"{path}: {key} is M-Film's own and stands for no real film: it cannot be measured against one")
        got = _measured(record)
        shot = re.search(r"\d{4}-\d{2}-\d{2}", record.fields["date"])
        expiry = re.search(r"\d{4}-\d{2}", record.fields["film expiry"])
        out[key] = State(key, "measured", colour=got.get("colour"), tone=got["tone"], paired=record.name,
                         shot=shot.group(0) if shot else record.fields["date"],
                         expired=(expiry.group(0) < shot.group(0)[:7]) if shot and expiry else None)
    return out


# ---------------------------------------------------------------- the words

def line(s, lang="en"):
    """A film stock's state as a line under its picture."""
    if s.kind == "measured":
        roll = {True: ("expired", "หมดอายุแล้ว"), False: ("in date", "ยังไม่หมดอายุ"), None: ("", "")}[s.expired][lang == "th"]
        if lang == "th":
            return f"วัดเทียบกับฟิล์มจริงแล้ว · {s.numbers().replace('colour', 'สี').replace('tone', 'โทน')} · ถ่ายม้วน {s.shot}" + (f" ฟิล์ม{roll}" if roll else "")
        return f"Measured against the film · {s.numbers()} · roll shot {s.shot}" + (f", {roll}" if roll else "")
    return {"en": {"style": "In the style of the film · not measured yet",
                   "impression": "An impression of infrared film · not measured yet",
                   "own": "M-Film's own · stands for no real film"},
            "th": {"style": "ในสไตล์ของฟิล์มนี้ · ยังไม่ได้วัด",
                   "impression": "ภาพจำลองความรู้สึกของฟิล์มอินฟราเรด · ยังไม่ได้วัด",
                   "own": "ลุคของ M-Film เอง · ไม่ได้แทนฟิล์มจริงตัวใด"}}[lang][s.kind]


def count(all_states, lang, how, every=None):
    """The count of film stocks in each state, as a paragraph; how and every: the links."""
    by = {k: [s for s in all_states.values() if s.kind == k] for k in ("measured", "style", "impression", "own")}
    n, m = len(all_states), len(by["measured"])
    names = lambda group: ", ".join(s.name for s in group)       # noqa: E731
    if lang == "th":
        out = (f"**ยังไม่มี film stock ใดใน {n} แบบที่วัดเทียบกับฟิล์มจริงแล้ว**" if not m else
               f"**มี film stock {m} แบบจาก {n} แบบที่วัดเทียบกับฟิล์มจริงแล้ว:** {names(by['measured'])}")
        out += f" {'อีก ' if m else ''}{len(by['style'])} แบบเป็นลุค \"ในสไตล์ของ\" ฟิล์มนั้น และยังไม่ได้วัด"
        if by["impression"]:
            out += (f" {len(by['impression'])} แบบ ({names(by['impression'])}) เป็นภาพจำลองความรู้สึกของฟิล์มอินฟราเรด "
                    "เพราะ Canon ที่ยังมีฟิลเตอร์อินฟราเรดอยู่บันทึกสิ่งที่ฟิล์มแบบนั้นบันทึกไม่ได้")
        if by["own"]:
            out += f" และ {len(by['own'])} แบบ ({names(by['own'])}) เป็นลุคของ M-Film เอง ไม่ได้แทนฟิล์มจริงตัวใด"
        out += (f" film stock จะนับว่าวัดแล้วเมื่อสีและโทนอยู่ใน pass mark ของสแกนจากแล็บของฟิล์มจริงม้วนหนึ่ง ที่ถ่ายคู่กับ Canon: "
                f"[วิธีที่ film stock จะนับว่าวัดแล้ว]({how})")
        return out + (f" · [สถานะของ film stock ทุกตัว]({every})" if every else "")
    out = (f"**None of the {n} film stocks is measured against the real film yet.**" if not m else
           f"**{m} of the {n} film stocks {'is' if m == 1 else 'are'} measured against the real film:** {names(by['measured'])}.")
    out += f" {len(by['style'])} {'more ' if m else ''}are in the style of their film and not measured yet."
    if by["impression"]:
        out += (f" {len(by['impression'])} ({names(by['impression'])}) {'is an impression' if len(by['impression']) == 1 else 'are impressions'} "
                "of infrared film: a Canon with its infrared filter in place does not record what that film does.")
    if by["own"]:
        out += f" {len(by['own'])} ({names(by['own'])}) {'is' if len(by['own']) == 1 else 'are'} M-Film's own and {'stands' if len(by['own']) == 1 else 'stand'} for no real film."
    out += (" A film stock counts as measured when its colour and tone come within the pass marks of a lab scan of a roll of the real film, "
            f"shot beside a Canon: [how a film stock becomes measured]({how}).")
    return out + (f" [Every film stock's state →]({every})" if every else "")


# ---------------------------------------------------------------- the pages

CELL = re.compile(r'(<a href="docs/films/([\w-]+)\.jpg">.*?</sub>)(?:<br><sub><i>.*?</i></sub>)?(?=</td>)')


def render(all_states, root=ROOT):
    """The pages as they should be: {path: text}."""
    out = {}
    for name, lang, how, every in PAGES:
        path = Path(root) / name
        text = path.read_text(encoding="utf-8")
        if text.count(START) != 1 or text.count(END) != 1:
            raise measure.Bad(f"{path}: needs one {START} and one {END}")
        text = text[:text.index(START) + len(START)] + "\n" + count(all_states, lang, how, every) + "\n" + text[text.index(END):]
        if name == "FILMS.md":
            seen = []

            def cell(m):
                seen.append(m.group(2))
                if m.group(2) not in all_states:
                    raise measure.Bad(f"{path}: {m.group(2)} is not on the shelf")
                return m.group(1) + "<br><sub><i>" + line(all_states[m.group(2)]) + "</i></sub>"

            text = CELL.sub(cell, text)
            if sorted(seen) != sorted(all_states):
                raise measure.Bad(f"{path} and the shelf differ: {', '.join(sorted(set(seen) ^ set(all_states)))}")
        out[path] = text
    return out


def claims(all_states, root=ROOT):
    """The lines of the public texts that say a film stock matches its film, when nothing is measured that could
    bear it out: [(file, line number, the line)]."""
    if any(s.kind == "measured" for s in all_states.values()):
        return []       # then such a line may be true of a film stock; which one is for a person to read
    found = []
    for name in [p[0] for p in PAGES] + TEXTS:
        for i, text in enumerate((Path(root) / name).read_text(encoding="utf-8").splitlines(), 1):
            if any(re.search(c, text, re.I) for c in CLAIMS):
                found.append((name, i, text.strip()[:120]))
    return found


def main():
    ap = argparse.ArgumentParser(description="Every film stock's state, on the pages people read.")
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()
    try:
        all_states = states()
        pages = render(all_states)
    except measure.Bad as e:
        sys.exit(f"states: {e}")
    kinds = [s.kind for s in all_states.values()]
    said = ", ".join(f"{kinds.count(k)} {k}" for k in ("measured", "style", "impression", "own"))
    if a.check:
        stale = [str(p.relative_to(ROOT)) for p, text in pages.items() if p.read_text(encoding="utf-8") != text]
        bad = claims(all_states)
        if stale or bad:
            sys.exit("states:" + ("\n  not what the shelf and the references say (run ml-dev/films/states.py): " + ", ".join(stale) if stale else "")
                     + "".join(f"\n  {n}:{i} says a film stock matches its film, and none is measured: {t}" for n, i, t in bad))
        print(f"states: {len(pages)} pages say what the shelf and the references do ({said}), and nothing claims a match")
        return
    for path, text in pages.items():
        if path.read_text(encoding="utf-8") != text:
            path.write_text(text, encoding="utf-8")
            print(f"states: wrote {path.relative_to(ROOT)}")
    print(f"states: {len(all_states)} film stocks: {said}")


if __name__ == "__main__":
    main()
