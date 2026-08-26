"""Extract the set of shows present in each downloaded revision of the sheet.

Layouts changed a lot over four years, so this does not assume fixed columns.
It reads every cell of every sheet and keeps strings that look like a show
name, then the caller diffs consecutive revisions to find additions and
removals.

Written to be over-inclusive here and filtered afterwards: a missed show is
invisible, whereas noise is obvious when the diff is eyeballed.
"""
import json
import os
import re
import sys

from openpyxl import load_workbook

REV_DIR = r"C:/Users/yischoen/.copilot/session-state/7b7cab1a-e3ed-4d93-8833-3f514326952c/files/revisions"

# Rows that are structure, notes or timings rather than shows.
NOISE = re.compile(
    r"^(time|source|duration|when|notes?|todo|ideas?|not done|already doing|removed"
    r"|consider(ing)?|more ideas|daily|weekly|news|sunday|monday|tuesday|wednesday"
    r"|thursday|friday|saturday|shabbat|shabbos|matza|day|min(utes)?|hours?|\d+"
    r"|.*gets home.*|.*fridays are different.*)$",
    re.I,
)
TIME = re.compile(r"^\d{1,2}[:.]\d{2}")
DURATION = re.compile(r"^\d+\s*-\s*\d+$")


def clean(value):
    """A show name from a cell, or None."""
    if value is None:
        return None
    s = str(value).strip()
    if not s or len(s) < 4 or len(s) > 70:
        return None
    # Drop a trailing parenthetical, which is where durations and day patterns
    # live: "Life Kit (15 - 25 minutes, generally Mon + Tue + Thu)".
    s = re.sub(r"\s*\([^)]*\)\s*$", "", s).strip()
    s = s.strip(" -–—:•")
    if not s or len(s) < 4:
        return None
    if TIME.match(s) or DURATION.match(s) or NOISE.match(s):
        return None
    # Sentences are notes, not show names.
    if s.count(" ") > 9 or s.endswith("."):
        return None
    if "http" in s.lower() and " " not in s:
        return None
    return s


def norm(s):
    s = s.lower()
    s = re.sub(r"\bthe\b|\bpodcast\b|\bshow\b|\bwith\b|\bby\b|\ba\b", " ", s)
    s = re.sub(r"[^a-z0-9 ]+", " ", s)
    return re.sub(r"\s+", " ", s).strip()


SOURCE_TABS = {"weekly", "daily", "news"}


def shows_in(path):
    wb = load_workbook(path, data_only=True, read_only=True)
    found = {}
    for ws in wb.worksheets:
        # Only the three tabs that have existed throughout. The catalog and
        # schedule tabs added in 2026 carry hundreds of names and descriptions
        # that would swamp the diff with things that were never scheduled.
        if ws.title.strip().lower() not in SOURCE_TABS:
            continue
        for row in ws.iter_rows(values_only=True):
            for cell in row:
                name = clean(cell)
                if not name:
                    continue
                k = norm(name)
                if len(k) >= 4:
                    found.setdefault(k, name)
    wb.close()
    return found


def main():
    index = json.load(open(os.path.join(REV_DIR, "index.json"), encoding="utf8"))
    out = []
    for rev in index:
        if not os.path.exists(rev["file"]):
            continue
        try:
            found = shows_in(rev["file"])
        except Exception as exc:  # a corrupt export must not lose the rest
            print(f"  {rev['date']} rev {rev['id']}: FAILED {exc}", file=sys.stderr)
            continue
        out.append({**rev, "shows": found})
        print(f"  {rev['date']} rev {str(rev['id']).rjust(5)}  {len(found)} shows")
    with open("revision-shows.json", "w", encoding="utf8") as fh:
        json.dump(out, fh, indent=1, ensure_ascii=False)
    print(f"\nwrote revision-shows.json ({len(out)} revisions)")


if __name__ == "__main__":
    main()
