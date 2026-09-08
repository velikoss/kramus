# idef0-text-import

Converts a short, human-readable text description of a process into a
Ramus diagram. Two independent tools, one per notation, because Ramus has a
ready-made IDEF0 import path but no DFD equivalent:

- **IDEF0** → a Python script emits a classic `.idl` (AI0WIN/BPWin) file,
  imported through Ramus's own existing "Import from IDL" menu action.
  No Ramus code touched at all.
- **DFD** → a small Java tool (lives in `idef0-common`, see
  `com.ramussoft.idef0.textimport`) since there's no text-interchange format
  for DFD to piggyback on; it calls the same underlying API
  (`DataPlugin.createFunction`, `MovingArea`/`SectorRefactor`) the GUI itself
  uses to place boxes and connect them, headlessly.

## IDEF0 usage

```bash
python idef0_text_to_idl.py model.txt model.idl
```

Then in Ramus: menu **IDEF0 → «Импортировать из IDL»** → pick `model.idl`.
Ramus creates a brand-new diagram from it and opens it automatically.

The script always writes the `.idl` file as **cp1251** — that's the encoding
Ramus's importer hardcodes, so don't re-save/re-encode the output file.

## DSL syntax

```
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
```

- One `diagram: IDEF0 "<title>"` header line.
- `function <id>: "<name>"` starts a box. `<id>` is any word, used only for
  cross-references within this file.
- Indented port lines (`input:` / `control:` / `output:` / `mechanism:`)
  attach an ICOM arrow to that box:
  - `"<text>"` — a new arrow, drawn from/to the diagram's outer frame.
  - `<id>.output` — reuse another function's **already-declared** output
    (that function must have `output: "..."` written on it) and draw an
    internal, function-to-function arrow instead of one going to the frame.
    A box can have any number of inputs/controls/outputs/mechanisms.

## Layout

Boxes are placed in the classic IDEF0 diagonal "staircase" order (box *N*
lower-and-to-the-right of box *N-1*). External ICOM arrows attach straight
to the nearest frame edge on the correct side (left=input, top=control,
right=output, bottom=mechanism); internal arrows use an orthogonal route
with a dedicated lane per arrow so parallel lines don't overlap. Arrow
labels are left for Ramus to center on the line by default; the script only
overrides a label's position (still without a tilde/squiggle connector) when
its own collision check finds it would overlap a neighboring label.

## IDEF0 known limitations

- IDEF0 only — the `.idl`/AI0WIN format this reuses has no DFD equivalent
  (see the DFD tool below instead).
- One flat diagram per file (no drill-down/decomposition into child
  diagrams, no arrow branch/join/tunnel edge cases).
- Layout is a simple staircase + orthogonal router, tuned for the small
  diagrams (a handful of boxes) this kind of text description typically
  describes — not a general graph-layout solver.

## DFD usage

Unlike the IDEF0 path, this one needs a real JVM (it's compiled Java, part
of the `idef0-common` Gradle module) — there's nothing like `.idl` for DFD
to import through.

1. In Ramus, **File → New**, save as an empty model, e.g. `model.rms`.
2. Write your DSL (see syntax below), e.g. `diagram.txt`.
3. Run:
   ```bash
   ./gradlew :idef0-common:dfdTextImport -PmodelFile=model.rms -PdslFile=diagram.txt
   ```
4. Open (or re-open) `model.rms` in Ramus — the new DFD diagram is already
   in the project tree.

### DFD DSL syntax

```
diagram: DFD "Обработка заявки"
external E1: "Клиент"
process P1: "Принять заявку"
process P2: "Обработать заявку"
store D1: "База заявок"
flow: E1 -> P1 "Новая заявка"
flow: P1 -> D1 "Сохранить заявку"
flow: D1 -> P2 "Заявка на обработку"
flow: P2 -> E1 "Результат"
```

- One `diagram: DFD "<title>"` header line.
- `process` / `external` / `store <id>: "<name>"` declares a node.
- `flow: <fromId> -> <toId> "<label>"` connects two nodes; multiple flows
  can share a node freely (fan-in/fan-out), unlike the IDEF0 DSL's
  single-reference-per-arrow model.

### DFD layout

Nodes are placed with a layered ("Sugiyama-lite") algorithm: a node with no
incoming flow starts at layer 0, everything else sits one layer past its
furthest-upstream predecessor, layers run left to right, and nodes within a
layer are spread evenly top to bottom. Every flow leaves its source on the
right side and enters its target on the left, routed as a straight line
(same row) or a simple 3-segment orthogonal jog (different rows) — plain
and readable for the modest node counts this kind of DSL usually describes,
not a general-purpose graph router.

### DFD known limitations

- Box sizing/position is written directly into `Function.setBounds()` in
  Ramus's native canvas units (not tied to any text-interchange scale), and
  the constants in `DfdLayout.java` are a first-pass estimate calibrated
  from `IDEFPanel.DEFAULT_WIDTH/HEIGHT` — nudge `NODE_W`/`NODE_H`/`PAGE_*`
  there if boxes render too big/small/sparse relative to Ramus's own
  defaults once you've seen a real render.
- Skips the default per-type font/color styling `MovingArea`'s own GUI path
  applies (bold 8pt for stores/externals, etc.) — boxes get engine defaults
  instead; cosmetic only.
- No DFD-S notation variant, no branch/join flows, no arrow labels beyond a
  single stream name per flow.
