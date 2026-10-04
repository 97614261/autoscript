"""Bounded signature-shape inventory, never an interpreter or an equivalence claim."""
import collections
import json
import math
import re


def argument_shape(text):
    if not isinstance(text, str) or len(text.encode("utf-8")) > 8192:
        return {"status": "unparsed", "reason": "argument-byte-budget"}
    if not text.strip():
        return {"status": "shape-only", "arity": 0, "types": []}
    values, start, quoted, escaped = [], 0, False, False
    # No regex splitting of quoted commas or nested function arguments.
    stack = []
    for index, char in enumerate(text):
        if quoted:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
            continue
        if char == '"':
            quoted = True
        elif char in "([{":
            stack.append(char)
            if len(stack) > 16:
                return {"status": "unparsed", "reason": "argument-depth-budget"}
        elif char in ")]}":
            if not stack or "([{".index(stack.pop()) != ")]}".index(char):
                return {"status": "unparsed", "reason": "unbalanced-arguments"}
        elif char == "," and not stack:
            values.append(text[start:index].strip())
            start = index + 1
            if len(values) >= 64:
                return {"status": "unparsed", "reason": "argument-count-budget"}
    if quoted or stack:
        return {"status": "unparsed", "reason": "unbalanced-arguments"}
    values.append(text[start:].strip())
    if any(not value for value in values):
        return {"status": "unparsed", "reason": "empty-argument"}
    types = []
    for value in values:
        try:
            literal = json.loads(value)
        except (ValueError, RecursionError):
            literal = None
        if isinstance(literal, str):
            kind = "string-literal"
        elif type(literal) is int and -(2**63) <= literal < 2**63:
            kind = "integer-literal"
        elif type(literal) is float and math.isfinite(literal):
            kind = "number-literal"
        elif type(literal) is bool:
            kind = "boolean-literal"
        elif re.fullmatch(r"(?:[^\W\d]|_)\w*", value):
            kind = "name-untyped"
        else:
            kind = "expression-unconfirmed"
        types.append(kind)
    return {"status": "shape-only", "arity": len(values), "types": types}


def audit_functions(issues, job):
    """Private names/locations only in local reports; summary contains trusted aggregates."""
    grouped = {}
    for issue in issues:
        job.check()
        record = issue.get("record") or {}
        name = record.get("_函数名字")
        if not name:
            continue
        if len(grouped) >= 256 and name not in grouped:
            raise ValueError("function inventory exceeds budget")
        item = grouped.setdefault(name, {"name": name, "status": "unresolved", "calls": [],
            "requiredEvidence": ["parameter-types", "return-type", "index-basis", "side-effects", "cancellation-and-errors"]})
        item["calls"].append({"source": issue["source"], "line": issue["line"],
            "shape": argument_shape(record.get("_函数参数", ""))})
    items = list(grouped.values())
    counts = collections.Counter(call["shape"]["status"] for item in items for call in item["calls"])
    return {"version": 1, "status": "static-audit-only", "functions": items,
        "summary": {"distinctFunctions": len(items), "callSites": sum(counts.values()),
            "unresolvedFunctions": len(items), "argumentShapes": dict(counts)}}
