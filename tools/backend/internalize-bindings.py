#!/usr/bin/env python3
"""Keep generated Kotlin declarations inside the SDK module."""
import re
import sys
from pathlib import Path

pattern = re.compile(r"^(?:public )?((?:(?:open|data|sealed|enum|annotation|value|suspend|inline|abstract|external|const) )*(?:class|interface|object|fun|val|var|typealias)\b)", re.MULTILINE)
for path in Path(sys.argv[1]).rglob("*.kt"):
    text = pattern.sub(r"internal \1", path.read_text())
    # Pinned generator emits free functions after its final custom type alias.
    marker = "internal typealias FfiConverterTypeGeneratedId = FfiConverterString"
    if marker not in text:
        raise RuntimeError("Pinned binding layout changed")
    head, tail = text.rsplit(marker, 1)
    tail = re.sub(r"(?m)^([ \t]*(?:@Throws\([^\n]+\) )?)fun ", r"\1internal fun ", tail)
    tail = tail.replace("*/ fun ", "*/ internal fun ")
    path.write_text(head + marker + tail)
