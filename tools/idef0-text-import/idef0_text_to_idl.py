#!/usr/bin/env python3
"""Convert a simple IDEF0 text description into an .idl file that Ramus can
import via the built-in menu action: IDEF0 -> "Импортировать из IDL".

DSL example (see README.md for full syntax):

    diagram: IDEF0 "Обработка заказа"
    function A1: "Принять заказ"
      input: "Заказ клиента"
      control: "Регламент обработки"
      output: "Подтверждённый заказ"
      mechanism: "Менеджер"
    function A2: "Проверить оплату"
      input: A1.output
      control: "Договор с банком"
      output: "Оплаченный заказ"
    function A3: "Отгрузить товар"
      input: A2.output
      mechanism: "Склад"

Usage:
    python idef0_text_to_idl.py model.txt model.idl

The output file MUST be read by Ramus as cp1251 (that's what its "Import
from IDL" action hardcodes), so this script always writes cp1251.
"""

import argparse
import re
import sys

PORTS = ("input", "control", "output", "mechanism")
SIDE_LETTER = {"input": "I", "control": "C", "output": "O", "mechanism": "M"}

# Normalized-page constants the Ramus IDL importer scales by
# (screenX = (x - 0.01) * 820, screenY = (y - 0.015) * 440; y grows downward).
PAGE_LEFT = 0.06
PAGE_RIGHT = 0.97
PAGE_TOP = 0.08
PAGE_BOTTOM = 0.97

BOX_W = 0.15
BOX_H = 0.11

LABEL_APPROX_W = 0.012   # per character, rough
LABEL_APPROX_H = 0.03


class ParseError(Exception):
    pass


class PortEntry:
    def __init__(self, text=None, ref=None):
        self.text = text          # literal label text, or None if this is a reference
        self.ref = ref            # (function_id, port) this references, or None
        self.consumed = False     # set True once used as the source of an internal arrow
        self.also_frame = False   # "+ frame": keep the border arrow even when consumed


class Function:
    def __init__(self, fid, name, index):
        self.id = fid
        self.name = name
        self.index = index  # 1-based BOX index
        self.detail = None  # id of the diagram this box decomposes into, if any
        self.ports = {p: [] for p in PORTS}
        self.cx = 0.0
        self.cy = 0.0
        self.w = BOX_W  # narrowed by the layout when many boxes share one row

    @property
    def x0(self):
        return self.cx - self.w / 2

    @property
    def x1(self):
        return self.cx + self.w / 2

    @property
    def y0(self):  # top edge (smaller y)
        return self.cy - BOX_H / 2

    @property
    def y1(self):  # bottom edge (larger y)
        return self.cy + BOX_H / 2


# ---------------------------------------------------------------------------
# DSL parsing
# ---------------------------------------------------------------------------

DIAGRAM_RE = re.compile(r'diagram:\s*IDEF0\s*"([^"]*)"\s*(?:\[([^\]]+)\])?\s*$', re.IGNORECASE)
FUNCTION_RE = re.compile(r'function\s+(\w+)\s*:\s*"([^"]*)"\s*$', re.IGNORECASE)
DETAIL_RE = re.compile(r'detail\s*:\s*(\S+)\s*$', re.IGNORECASE)
PORT_RE = re.compile(r'(input|control|output|mechanism)\s*:\s*(.+?)\s*$', re.IGNORECASE)
QUOTED_RE = re.compile(r'^"([^"]*)"(\s*\+\s*frame)?$', re.IGNORECASE)
REF_RE = re.compile(r'^(\w+)\.(input|control|output|mechanism)$', re.IGNORECASE)


def parse_dsl(text):
    diagram_title = None
    diagram_id = "A-0"
    functions = {}
    order = []
    current = None

    for lineno, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue

        m = DIAGRAM_RE.match(line)
        if m:
            diagram_title = m.group(1)
            if m.group(2):
                diagram_id = m.group(2).strip()
            continue

        m = DETAIL_RE.match(line)
        if m:
            if current is None:
                raise ParseError(f"line {lineno}: 'detail:' outside of any 'function' block")
            current.detail = m.group(1)
            continue

        m = FUNCTION_RE.match(line)
        if m:
            fid, name = m.group(1), m.group(2)
            if fid in functions:
                raise ParseError(f"line {lineno}: duplicate function id '{fid}'")
            current = Function(fid, name, len(order) + 1)
            functions[fid] = current
            order.append(fid)
            continue

        m = PORT_RE.match(line)
        if m:
            if current is None:
                raise ParseError(f"line {lineno}: port line outside of any 'function' block")
            port = m.group(1).lower()
            value = m.group(2).strip()
            qm = QUOTED_RE.match(value)
            rm = REF_RE.match(value)
            if qm:
                entry = PortEntry(text=qm.group(1))
                entry.also_frame = qm.group(2) is not None
                current.ports[port].append(entry)
            elif rm:
                current.ports[port].append(PortEntry(ref=(rm.group(1), rm.group(2).lower())))
            else:
                raise ParseError(
                    f'line {lineno}: expected "text" or <id>.<port> after "{port}:", got: {value}'
                )
            continue

        raise ParseError(f"line {lineno}: unrecognized line: {raw}")

    if diagram_title is None:
        raise ParseError('missing header line: diagram: IDEF0 "..."')
    if not order:
        raise ParseError("model has no functions")

    funcs = [functions[fid] for fid in order]

    # Resolve references up front: validate the target exists and has a literal
    # entry to borrow the label text from.
    for f in funcs:
        for port in PORTS:
            for entry in f.ports[port]:
                if entry.ref is None:
                    continue
                rfid, rport = entry.ref
                if rfid not in functions:
                    raise ParseError(f"function '{f.id}': reference to unknown function '{rfid}'")
                target_literals = [e for e in functions[rfid].ports[rport] if e.text is not None]
                if not target_literals:
                    raise ParseError(
                        f"function '{f.id}': '{rfid}.{rport}' has no literal text to reference "
                        f"(declare it as {rport}: \"...\" on function {rfid} first)"
                    )
                entry.text = target_literals[0].text
                target_literals[0].consumed = True

    return diagram_title, diagram_id, funcs


# ---------------------------------------------------------------------------
# Layout
# ---------------------------------------------------------------------------

class Layout:
    def __init__(self, funcs):
        self.funcs = funcs
        self._placed_labels = []   # list of (x, y, w, h) already-used label boxes
        self._border_counters = {"I": 0, "C": 0, "O": 0, "M": 0}
        self._lane_counter = 0

    # horizontal gap kept between one box's right edge and the next box's left
    # edge, so an output can always run right-then-down into the next input
    # instead of doubling back on itself.
    BOX_GAP = 0.035

    def place_boxes(self):
        n = len(self.funcs)
        x0 = PAGE_LEFT + BOX_W / 2 + 0.11
        x1 = PAGE_RIGHT - BOX_W / 2 - 0.05
        y0 = PAGE_TOP + BOX_H / 2 + 0.10
        y1 = PAGE_BOTTOM - BOX_H / 2 - 0.10

        width = BOX_W
        if n > 1:
            step = (x1 - x0) / (n - 1)
            width = min(BOX_W, max(0.085, step - self.BOX_GAP))

        for i, f in enumerate(self.funcs):
            t = 0.5 if n == 1 else i / (n - 1)
            f.w = width
            f.cx = x0 + t * (x1 - x0)
            f.cy = y0 + t * (y1 - y0)

    def _spread(self, y0, y1, k, m):
        # k-th of m evenly spaced points strictly between y0 and y1
        return y0 + (k + 1) / (m + 1) * (y1 - y0)

    def _next_border_token(self, letter):
        self._border_counters[letter] += 1
        return f"{letter}{self._border_counters[letter]}"

    def _next_lane(self):
        self._lane_counter += 1
        return self._lane_counter

    def _label_anchor(self, path, want_horizontal_corridor):
        # pick the longest axis-aligned segment to anchor the label to
        best = None
        best_len = -1
        for (x0, y0), (x1, y1) in zip(path, path[1:]):
            length = abs(x1 - x0) + abs(y1 - y0)
            horizontal = abs(x1 - x0) >= abs(y1 - y0)
            if want_horizontal_corridor is not None and horizontal != want_horizontal_corridor:
                continue
            if length > best_len:
                best_len = length
                best = ((x0, y0), (x1, y1))
        if best is None:
            best = (path[0], path[1])
        (x0, y0), (x1, y1) = best
        return (x0 + x1) / 2, (y0 + y1) / 2, abs(x1 - x0) >= abs(y1 - y0)

    def place_label(self, path, text):
        mx, my, horizontal = self._label_anchor(path, None)
        w = max(0.04, LABEL_APPROX_W * len(text))
        h = LABEL_APPROX_H
        offset = 0.018
        # default: offset perpendicular to the anchor segment
        cand_x = mx
        cand_y = my - offset if horizontal else my
        cand_x = mx if horizontal else mx + offset

        attempt = 0
        while attempt < 6 and self._collides(cand_x, cand_y, w, h):
            attempt += 1
            if horizontal:
                cand_y = my - offset - attempt * (h * 0.9)
            else:
                cand_x = mx + offset + attempt * (w * 0.6)

        self._placed_labels.append((cand_x, cand_y, w, h))
        if attempt == 0:
            return None  # no override needed, let Ramus center it on the segment
        return cand_x, cand_y

    def _collides(self, x, y, w, h):
        ax0, ay0, ax1, ay1 = x - w / 2, y - h / 2, x + w / 2, y + h / 2
        for (px, py, pw, ph) in self._placed_labels:
            bx0, by0, bx1, by1 = px - pw / 2, py - ph / 2, px + pw / 2, py + ph / 2
            if ax0 < bx1 and ax1 > bx0 and ay0 < by1 and ay1 > by0:
                return True
        return False

    def route_external(self, f, port, k, m):
        """Straight segment between the diagram border and one box side."""
        letter = SIDE_LETTER[port]
        if port == "input":
            y = self._spread(f.y0, f.y1, k, m)
            outer = (PAGE_LEFT * 0.4, y)
            inner = (f.x0, y)
            path = [outer, inner]
        elif port == "control":
            x = self._spread(f.x0, f.x1, k, m)
            outer = (x, PAGE_TOP * 0.4)
            inner = (x, f.y0)
            path = [outer, inner]
        elif port == "mechanism":
            # mechanism enters the box from below: the path has to run
            # border -> box, matching SOURCE BORDER / SINK BOX.
            x = self._spread(f.x0, f.x1, k, m)
            outer = (x, PAGE_BOTTOM + (1 - PAGE_BOTTOM) * 0.5)
            inner = (x, f.y1)
            path = [outer, inner]
        else:  # output
            y = self._spread(f.y0, f.y1, k, m)
            outer = (f.x1, y)
            inner = (PAGE_RIGHT + (1 - PAGE_RIGHT) * 0.5, y)
            path = [outer, inner]
        border_token = self._next_border_token(letter)
        return path, border_token

    def route_internal(self, src_f, dst_f, dst_port, k_src, m_src, k_dst, m_dst):
        """Orthogonal 4-point route from src_f's output side to dst_f's given side."""
        sy = self._spread(src_f.y0, src_f.y1, k_src, m_src)
        start = (src_f.x1, sy)

        lane = self._next_lane()
        if dst_port == "input":
            dy = self._spread(dst_f.y0, dst_f.y1, k_dst, m_dst)
            end = (dst_f.x0, dy)
            corridor_x = min(src_f.x1, dst_f.x0) + lane * 0.01
            corridor_x = min(corridor_x, dst_f.x0 - 0.01)
            corridor_x = max(corridor_x, src_f.x1 + 0.01)
            path = [start, (corridor_x, sy), (corridor_x, dy), end]
        elif dst_port == "control":
            dx = self._spread(dst_f.x0, dst_f.x1, k_dst, m_dst)
            end = (dx, dst_f.y0)
            corridor_y = min(src_f.y0, dst_f.y0) - 0.03 - lane * 0.008
            path = [start, (start[0], corridor_y), (dx, corridor_y), end]
        else:  # mechanism
            dx = self._spread(dst_f.x0, dst_f.x1, k_dst, m_dst)
            end = (dx, dst_f.y1)
            corridor_y = max(src_f.y1, dst_f.y1) + 0.03 + lane * 0.008
            path = [start, (start[0], corridor_y), (dx, corridor_y), end]
        return path


# ---------------------------------------------------------------------------
# IDL emission
# ---------------------------------------------------------------------------

def escape_idl_text(text):
    return text.replace("\\", "\\\\").replace("'", "\\'")


def fmt_point(p):
    x, y = p
    return f"({x:.4f},{y:.4f})"


def fmt_path(points):
    return " ".join(fmt_point(p) for p in points)


class IdlWriter:
    def __init__(self):
        self.lines = []

    def raw(self, line):
        self.lines.append(line + ";")

    def quoted(self, keyword, text):
        self.raw(f"{keyword} '{escape_idl_text(text)}'")

    def labeled(self, keyword, text):
        self.raw(f"{keyword} '{{}}" + escape_idl_text(text) + "'")

    def render(self):
        return "\n".join(self.lines) + "\n"


def build_idl(diagrams):
    """diagrams: list of (title, diagram_id, funcs), parent first."""
    w = IdlWriter()
    w.quoted("MODEL", diagrams[0][0])
    for title, diagram_id, funcs in diagrams:
        emit_diagram(w, title, diagram_id, funcs)
    return w.render()


def emit_diagram(w, diagram_title, diagram_id, funcs):
    layout = Layout(funcs)
    layout.place_boxes()

    w.raw(f"DIAGRAM GRAPHIC {diagram_id}")
    w.quoted("TITLE", diagram_title)

    for f in funcs:
        w.raw(f"BOX {f.index}")
        w.labeled("NAME", f.name)
        w.raw(f"BOX COORDINATES {fmt_point((f.x0, f.y1))} {fmt_point((f.x1, f.y0))}")
        if f.detail:
            w.raw(f"DETAIL REFERENCE N {f.detail}")
        w.raw("ENDBOX")

    seg_index = 0

    def emit_segment(path, label_text, source_token, sink_token):
        nonlocal seg_index
        seg_index += 1
        w.raw(f"ARROWSEG {seg_index}")
        w.raw(f"SOURCE {source_token}")
        w.raw(f"PATH {fmt_path(path)}")
        # LABEL must NOT start with '{': IDLImporter.createSegment() then tries to
        # read font/colour indices out of it and throws on the unguarded token read.
        # BOX NAME is the opposite — it needs the '{}' prefix (it does substring(1, i)).
        w.quoted("LABEL", label_text)
        override = layout.place_label(path, label_text)
        if override is not None:
            w.raw(f"LABEL COORDINATES {fmt_point(override)}")
        w.raw(f"SINK {sink_token}")
        w.raw("ENDSEG")

    # external (border) arrows: inputs/controls/mechanisms not fed by another
    # function's output, plus outputs nobody consumes internally.
    for f in funcs:
        for port in ("input", "control", "mechanism"):
            entries = f.ports[port]
            m = len(entries)
            for k, entry in enumerate(entries):
                if entry.ref is not None:
                    continue  # handled as an internal arrow below
                path, token = layout.route_external(f, port, k, m)
                box_token = f"BOX {f.index}{SIDE_LETTER[port]}1"
                emit_segment(path, entry.text, f"BORDER {token}", box_token)

        out_entries = f.ports["output"]
        m = len(out_entries)
        for k, entry in enumerate(out_entries):
            if entry.consumed and not entry.also_frame:
                continue  # goes to another function internally, not to the border
            path, token = layout.route_external(f, "output", k, m)
            box_token = f"BOX {f.index}O1"
            emit_segment(path, entry.text, box_token, f"BORDER {token}")

    # internal (function-to-function) arrows
    for f in funcs:
        for port in ("input", "control", "mechanism"):
            entries = f.ports[port]
            m = len(entries)
            for k, entry in enumerate(entries):
                if entry.ref is None:
                    continue
                src_f = next(x for x in funcs if x.id == entry.ref[0])
                src_out = src_f.ports["output"]
                k_src = next(i for i, e in enumerate(src_out) if e.text == entry.text and e.consumed)
                path = layout.route_internal(src_f, f, port, k_src, len(src_out), k, m)
                emit_segment(
                    path,
                    entry.text,
                    f"BOX {src_f.index}O1",
                    f"BOX {f.index}{SIDE_LETTER[port]}1",
                )

    w.raw("ENDDIAGRAM")


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("inputs", nargs="+", help="DSL text file(s); parent diagram first")
    ap.add_argument("-o", "--output", required=True, help="path to write the .idl file to (cp1251)")
    args = ap.parse_args()

    diagrams = []
    try:
        for path in args.inputs:
            with open(path, "r", encoding="utf-8") as fh:
                diagrams.append(parse_dsl(fh.read()))
        idl_text = build_idl(diagrams)
    except ParseError as e:
        print(f"error: {e}", file=sys.stderr)
        sys.exit(1)

    with open(args.output, "w", encoding="cp1251", errors="replace") as fh:
        fh.write(idl_text)

    total = sum(len(d[2]) for d in diagrams)
    print(f"wrote {args.output} ({len(diagrams)} diagram(s), {total} box(es)) — import in Ramus via "
          f"IDEF0 -> \"Импортировать из IDL\"")


if __name__ == "__main__":
    main()
