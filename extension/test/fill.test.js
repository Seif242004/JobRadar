const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const path = require("node:path");
const { JSDOM } = require("jsdom");
const { fill, plan } = require("../src/fill.js");

const PROFILE = {
  firstName: "Test",
  lastName: "Person",
  email: "test.person@example.com",
  phone: "+20 100 000 0000",
  city: "Cairo",
  linkedin: "https://www.linkedin.com/in/test-person",
};

function load(name) {
  const html = fs.readFileSync(path.join(__dirname, "fixtures", name), "utf8");
  return new JSDOM(html).window.document;
}

test("greenhouse: fills the contact fields it recognises and nothing else", () => {
  const doc = load("greenhouse-form.html");
  const result = fill(doc, PROFILE);

  assert.deepStrictEqual(result.filled.sort(), ["email", "firstName", "lastName", "linkedin", "phone"]);
  assert.strictEqual(doc.getElementById("first_name").value, "Test");
  assert.strictEqual(doc.getElementById("last_name").value, "Person");
  assert.strictEqual(doc.getElementById("email").value, "test.person@example.com");
  assert.strictEqual(doc.getElementById("phone").value, "+20 100 000 0000");
  // The LinkedIn question is found by its label, not by a stable id.
  assert.strictEqual(doc.getElementById("question_37369956002").value, "https://www.linkedin.com/in/test-person");
  // The country dropdown (a combobox) and the resume upload are left to the person.
  assert.strictEqual(doc.getElementById("country").value, "");
  assert.strictEqual(doc.getElementById("resume").value, "");
});

test("workable: fills by name and by label, leaves files, radios and free-text questions alone", () => {
  const doc = load("workable-form.html");
  const result = fill(doc, PROFILE);

  assert.deepStrictEqual(result.filled.sort(), ["city", "email", "firstName", "lastName", "phone"]);
  assert.strictEqual(doc.getElementById("firstname").value, "Test");
  assert.strictEqual(doc.getElementById("input_phone").value, "+20 100 000 0000");
  assert.strictEqual(doc.getElementById("city").value, "Cairo");
  assert.strictEqual(doc.getElementById("QA_12776465").value, "");
  assert.ok([...doc.querySelectorAll("input[type=radio]")].every((r) => !r.checked));
});

test("never submits: the form's submit event is not fired and the button is not clicked", () => {
  for (const name of ["greenhouse-form.html", "workable-form.html"]) {
    const doc = load(name);
    let submitted = false;
    let clicked = false;
    doc.querySelector("form").addEventListener("submit", () => (submitted = true));
    doc.querySelector("button[type=submit]").addEventListener("click", () => (clicked = true));
    fill(doc, PROFILE);
    assert.strictEqual(submitted, false);
    assert.strictEqual(clicked, false);
  }
});

test("a field the person already filled is kept", () => {
  const doc = load("greenhouse-form.html");
  doc.getElementById("first_name").value = "Typed";

  const result = fill(doc, PROFILE);

  assert.strictEqual(doc.getElementById("first_name").value, "Typed");
  assert.deepStrictEqual(result.skipped, [{ key: "firstName", reason: "already filled" }]);
});

test("input and change events fire so React-style forms register the value", () => {
  const doc = load("greenhouse-form.html");
  const events = [];
  const email = doc.getElementById("email");
  ["input", "change"].forEach((type) => email.addEventListener(type, () => events.push(type)));

  fill(doc, PROFILE);

  assert.deepStrictEqual(events, ["input", "change"]);
});

test("empty or missing profile values fill nothing", () => {
  const doc = load("workable-form.html");

  assert.deepStrictEqual(plan(doc, {}), []);
  assert.deepStrictEqual(plan(doc, { firstName: "   " }), []);
  assert.deepStrictEqual(plan(doc, null), []);
});

test("a page without a form is left alone", () => {
  const doc = new JSDOM("<p>No form here</p>").window.document;

  assert.deepStrictEqual(fill(doc, PROFILE), { filled: [], skipped: [] });
});
