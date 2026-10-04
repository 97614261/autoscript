"""Convert virtual plugin groups and real bounded image directories."""
import hashlib
import json
import re


def alias(prefix, value):
    return prefix + hashlib.sha256(value.encode("utf-8")).hexdigest()[:20]


def flow_name(value):
    edge = r"[A-Za-z0-9_\-\u4e00-\u9fff]"
    return bool(re.fullmatch(edge + r"(?:[A-Za-z0-9_\-\u4e00-\u9fff. ]{0,62}" + edge + r")?", value))


def groups(converter, project):
    name = "插件/插件分组.pz"
    if name not in converter.archive.entries:
        return []
    # Original pluginGroup.getGroupName/getGroupPlugins use CRLF and '|', not '┊'.
    text = converter.archive.text(name)
    if len(text.encode("utf-8")) > 1024 * 1024:
        raise ValueError("group document exceeds budget")
    output, aliases = [], []
    seen_names, members, mapped_names = set(), set(), set()
    from .core import record_lines
    for index, line in enumerate(record_lines(text), 1):
        converter.job.check()
        if not line:
            continue
        parts = line.split("|")
        source_name = parts[0]
        try:
            if (not source_name or any(ord(c) < 32 for c in source_name)
                    or source_name in seen_names or len(output) >= 64):
                raise ValueError("invalid, duplicate, or excessive group")
            converted_name = source_name if flow_name(source_name) else alias("group-", source_name)
            if converted_name in mapped_names:
                raise ValueError("group alias collision")
            ids = []
            for member in parts[1:]:
                if not member:
                    raise ValueError("group member missing")
                plugin = converter.plugin_path(member if member.endswith(".cj") else member + ".cj")
                flow_id = converter.flows[plugin]
                if flow_id in members or flow_id in ids:
                    raise ValueError("duplicate group membership")
                ids.append(flow_id)
            seen_names.add(source_name)
            mapped_names.add(converted_name)
            members.update(ids)
            output.append({"name": converted_name, "flowIds": ids})
            aliases.append({"sourceName": source_name, "name": converted_name, "flowIds": ids})
        except ValueError:
            converter.block(name, index, "legacy group membership is invalid, duplicated, or references a missing plugin")
    if output:
        destination = project / ".studio"
        destination.mkdir(exist_ok=True)
        (destination / "source-groups.json").write_text(json.dumps({"version": 1, "groups": output},
                                                        ensure_ascii=False, indent=2), encoding="utf-8")
    return aliases


def image_directories(converter, project):
    source_paths = set()
    for name in list(converter.archive.entries) + list(converter.archive.directories):
        if not name.startswith("图片/"):
            continue
        parts = name.rstrip("/").split("/")[1:]
        if name not in converter.archive.directories:
            parts = parts[:-1]
        for depth in range(1, len(parts) + 1):
            source_paths.add("/".join(parts[:depth]))
    aliases, destinations = {}, set()
    for source in sorted(source_paths, key=lambda value: (value.count("/"), value)):
        converter.job.check()
        parts = source.split("/")
        if len(parts) > 4 or len(aliases) >= 256:
            converter.block("images", 0, "legacy image directory depth or count exceeds current limits")
            continue
        parent = "/".join(parts[:-1])
        if parent and parent not in aliases:
            continue
        segment = parts[-1]
        reserved = re.fullmatch(r"CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]", segment.split(".")[0], re.I)
        valid = (1 <= len(segment) <= 40 and segment not in (".", "..")
                 and not segment.endswith(".") and not reserved
                 and all(ord(c) <= 65535 and (c.isalnum() or c in "._-") for c in segment))
        converted = segment if valid else alias("dir-", source)
        path = (aliases[parent] + "/" if parent else "") + converted
        if len(path) > 120 or path.casefold() in destinations:
            converted = alias("dir-", source)
            path = (aliases[parent] + "/" if parent else "") + converted
        if len(path) > 120 or path.casefold() in destinations:
            converter.block("images", 0, "legacy image directory alias conflicts with current limits")
            continue
        aliases[source] = path
        destinations.add(path.casefold())
        (project / "assets/images" / path).mkdir(parents=True, exist_ok=True)
    if aliases:
        destination = project / ".studio"
        destination.mkdir(exist_ok=True)
        (destination / "image-directories.json").write_text(json.dumps({"version": 1,
            "directories": sorted(aliases.values())}, ensure_ascii=False, indent=2), encoding="utf-8")
    return aliases
