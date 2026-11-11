// One-page CV template. All content comes from cv.json next to this file; the template holds no
// candidate text. Values are inserted as strings, which Typst renders literally, so characters
// such as #, *, _ or $ in a bullet can never be interpreted as markup.
#let cv = json("cv.json")

// Carlito is installed in the worker image; Libertinus ships inside Typst as the fallback.
#set page(paper: "us-letter", margin: (top: 0.5in, bottom: 0.45in, left: 0.65in, right: 0.65in))
#set text(font: ("Carlito", "Libertinus Serif"), size: 10pt, hyphenate: false, ligatures: false)
#set par(leading: 0.5em, spacing: 0.5em, justify: false)
#set list(marker: [–], indent: 0.4em, body-indent: 0.5em, spacing: 0.42em)

// Bold upper-case title with a rule under it.
#let section(title) = {
  v(7pt, weak: true)
  block(
    width: 100%,
    below: 5pt,
    inset: (bottom: 2.5pt),
    stroke: (bottom: 0.6pt),
    text(weight: "bold", size: 11pt, upper(title)),
  )
}

// Heading on the left, dates on the right, optional sub-heading below.
#let entry(e) = {
  grid(
    columns: (1fr, auto),
    column-gutter: 8pt,
    text(weight: "bold", e.title),
    if e.dates != none { e.dates },
  )
  if e.context != none { text(e.context) }
  if e.bullets.len() > 0 { list(..e.bullets.map(b => [#b])) }
  v(2pt, weak: true)
}

// ---------------- header ----------------
#align(center)[
  #text(size: 19pt, weight: "bold", upper(cv.name)) \
  #text(size: 9pt, cv.contacts.map(c => if c.url != none { link(c.url, c.text) } else { c.text }).join("  |  "))
  #if cv.headline != none [\ #cv.headline]
]

// ---------------- summary, when a confirmed one was chosen ----------------
#if cv.summary != none {
  section("Summary")
  [#cv.summary]
}

// ---------------- sections, in the order given ----------------
#for s in cv.sections {
  section(s.title)
  if s.kind == "entries" {
    for e in s.entries { entry(e) }
  } else if s.kind == "skills" {
    // Skill rows are "Category: items"; the category is bold.
    for row in s.rows {
      let parts = row.split(": ")
      if parts.len() > 1 [*#parts.at(0):* #parts.slice(1).join(": ") \ ] else [#row \ ]
    }
  } else {
    for l in s.lines [#l \ ]
  }
}
