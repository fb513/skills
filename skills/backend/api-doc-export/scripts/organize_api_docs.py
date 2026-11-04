#!/usr/bin/env python3
"""归档接口文档，并按文档仓库的目录层级生成 README。

这个脚本只执行确定性的工作：读取 layout 配置、按标题或 Path 规则分类、移动
Markdown 文件、生成索引。无法匹配的文档会以 JSON 形式报告给调用它的 AI，
不会创建“待分类”目录，也不会在分类不完整时部分移动文件。
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
from dataclasses import dataclass
from pathlib import Path


PATH_RE = re.compile(r"^\*\*Path:\*\*\s*/?([^\s]+)", re.MULTILINE)
TITLE_RE = re.compile(r"^#\s+(.+?)\s*$", re.MULTILINE)


@dataclass(frozen=True)
class Document:
    source: Path
    title: str
    paths: tuple[str, ...]


@dataclass(frozen=True)
class Classification:
    document: Document
    target: str | None
    reason: str
    candidates: tuple[str, ...] = ()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="按 API 文档层级归档 Markdown 文件")
    parser.add_argument("--source", required=True, type=Path, help="Java 导出器写入的临时目录")
    parser.add_argument("--out", required=True, type=Path, help="API 文档仓库目录")
    parser.add_argument("--layout", type=Path, help="分类配置，默认使用 out/.api-doc-layout.json")
    parser.add_argument("--dry-run", action="store_true", help="只输出分类计划，不移动文件")
    parser.add_argument("--json", action="store_true", help="输出机器可读 JSON")
    return parser.parse_args()


def load_layout(path: Path) -> dict:
    if not path.is_file():
        raise SystemExit(f"缺少分类配置: {path}")
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise SystemExit(f"分类配置不是有效 JSON: {path}: {exc}") from exc
    if not isinstance(data, dict) or not isinstance(data.get("rules"), list):
        raise SystemExit(f"分类配置缺少 rules 数组: {path}")
    if not isinstance(data.get("titleRules", {}), dict):
        raise SystemExit(f"分类配置的 titleRules 必须是对象: {path}")
    return data


def read_documents(source: Path) -> list[Document]:
    if not source.is_dir():
        raise SystemExit(f"导出临时目录不存在: {source}")
    documents: list[Document] = []
    for path in sorted(source.rglob("*.md")):
        if path.name == "README.md":
            continue
        text = path.read_text(encoding="utf-8")
        title_match = TITLE_RE.search(text)
        if not title_match:
            title = path.stem
        else:
            title = title_match.group(1).strip()
        paths = tuple(dict.fromkeys(normalize_path(x) for x in PATH_RE.findall(text)))
        documents.append(Document(path, title, paths))
    return documents


def normalize_path(value: str) -> str:
    value = value.strip().split("?", 1)[0].split("#", 1)[0]
    return value.strip("/")


def prefix_matches(path: str, prefix: str) -> bool:
    prefix = normalize_path(prefix)
    return path == prefix or path.startswith(prefix + "/")


def classify(document: Document, layout: dict) -> Classification:
    title_rules = layout.get("titleRules", {})
    if document.title in title_rules:
        rule = title_rules[document.title]
        target = rule.get("target") if isinstance(rule, dict) else rule
        prefixes = rule.get("pathPrefixes", []) if isinstance(rule, dict) else []
        if prefixes and (not document.paths or not all(
            any(prefix_matches(path, prefix) for prefix in prefixes)
            for path in document.paths
        )):
            return Classification(document, None, "title_scope_changed")
        if isinstance(target, str) and target:
            return Classification(document, target, "title")

    # titleRules 用于第三方、跨端或其它语义无法由路径唯一确定的文档。
    # 同一标题规则一旦作用域发生变化，必须交给 AI 重新判断，不能回退到宽泛路径规则。
    matches: set[str] = set()
    unmatched = False
    for path in document.paths:
        rules = [rule for rule in layout["rules"] if prefix_matches(path, rule["pathPrefix"])]
        if not rules:
            unmatched = True
            continue
        longest = max(len(normalize_path(rule["pathPrefix"])) for rule in rules)
        matches.update(rule["target"] for rule in rules
                       if len(normalize_path(rule["pathPrefix"])) == longest)

    if unmatched:
        return Classification(document, None, "unmatched_paths", tuple(sorted(matches)))

    if len(matches) == 1:
        target = next(iter(matches))
        return Classification(document, target, "path", tuple(sorted(matches)))
    if len(matches) > 1:
        return Classification(document, None, "multiple_targets", tuple(sorted(matches)))
    return Classification(document, None, "no_match")


def validate_target(target: str, out: Path) -> Path:
    relative = Path(target)
    if not target or relative == Path(".") or relative.is_absolute() or any(
        part.startswith(".") for part in relative.parts
    ) or ":" in target or "\\" in target:
        raise SystemExit(f"分类目标必须是文档仓库内的相对目录: {target}")
    resolved = (out / relative).resolve()
    out_resolved = out.resolve()
    if out_resolved not in resolved.parents and resolved != out_resolved:
        raise SystemExit(f"分类目标超出文档仓库: {target}")
    return resolved


def existing_documents(out: Path) -> dict[str, list[Path]]:
    result: dict[str, list[Path]] = {}
    for path in out.rglob("*.md"):
        if path.name == "README.md" or any(part.startswith(".") for part in path.relative_to(out).parts):
            continue
        match = TITLE_RE.search(path.read_text(encoding="utf-8"))
        if match:
            result.setdefault(match.group(1).strip(), []).append(path)
    return result


def validate_plan(plan: list[Classification], out: Path) -> list[dict]:
    errors: list[dict] = []
    destination_map: dict[Path, Document] = {}
    existing_by_title = existing_documents(out)
    batch_titles: set[str] = set()
    for item in plan:
        if item.target is None:
            errors.append({
                "file": item.document.source.name,
                "title": item.document.title,
                "paths": list(item.document.paths),
                "reason": item.reason,
                "candidates": list(item.candidates),
            })
            continue
        destination = validate_target(item.target, out) / item.document.source.name
        if item.document.title in batch_titles:
            errors.append({"file": item.document.source.name, "reason": "duplicate_title"})
        batch_titles.add(item.document.title)
        previous = destination_map.get(destination)
        if previous is not None and previous != item.document:
            errors.append({
                "file": item.document.source.name,
                "title": item.document.title,
                "reason": "duplicate_destination",
                "destination": str(destination),
            })
        destination_map[destination] = item.document

        for existing in existing_by_title.get(item.document.title, []):
            if existing.resolve() != destination.resolve():
                errors.append({
                    "file": item.document.source.name,
                    "title": item.document.title,
                    "reason": "existing_title_in_other_directory",
                    "existing": str(existing),
                    "destination": str(destination),
                })
        if destination.is_file():
            text = destination.read_text(encoding="utf-8")
            title_match = TITLE_RE.search(text)
            if not title_match or title_match.group(1).strip() != item.document.title:
                errors.append({
                    "file": item.document.source.name,
                    "title": item.document.title,
                    "reason": "destination_owner_conflict",
                    "existingPaths": sorted(old_paths),
                    "paths": list(item.document.paths),
                    "destination": str(destination),
                })
    return errors


def relative_link(path: Path, root: Path) -> str:
    # 保留中文文件名的可读性，只转义 Markdown 链接中最常见的空格。
    return path.relative_to(root).as_posix().replace(" ", "%20")


def section_lines(directory: Path, root: Path, level: int) -> list[str]:
    lines: list[str] = []
    heading = "#" * min(level, 6)
    for child in sorted(directory.iterdir(), key=lambda p: (not p.is_dir(), p.name)):
        if child.name == "README.md" or child.name.startswith("."):
            continue
        if child.is_dir():
            lines.extend([f"{heading} [{child.name}](./{relative_link(child, root)}/)", ""])
            lines.extend(section_lines(child, root, level + 1))
        elif child.suffix.lower() == ".md":
            lines.append(f"- [{child.stem}](./{relative_link(child, root)})")
    if lines and lines[-1] != "":
        lines.append("")
    return lines


def write_readme(out: Path, layout: dict) -> None:
    lines = [
        "# API 文档索引",
        "",
        "文档按接口调用目标和业务模块整理。文件内容保持原样，仅调整目录位置。",
        "",
    ]
    descriptions = layout.get("descriptions", {})
    top_dirs = [p for p in out.iterdir() if p.is_dir() and not p.name.startswith(".")]
    for directory in sorted(top_dirs, key=lambda p: p.name):
        lines.extend([f"## [{directory.name}](./{relative_link(directory, out)}/)", ""])
        description = descriptions.get(directory.name)
        if description:
            lines.extend([description, ""])
        lines.extend(section_lines(directory, out, 3))
    (out / "README.md").write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")


def execute(plan: list[Classification], out: Path, layout: dict) -> None:
    for item in plan:
        assert item.target is not None
        destination_dir = validate_target(item.target, out)
        destination_dir.mkdir(parents=True, exist_ok=True)
        destination = destination_dir / item.document.source.name
        if destination.resolve() != item.document.source.resolve():
            shutil.copy2(item.document.source, destination)
            item.document.source.unlink()
    write_readme(out, layout)


def result_payload(plan: list[Classification], errors: list[dict]) -> dict:
    return {
        "ok": not errors,
        "documents": [
            {
                "file": item.document.source.name,
                "title": item.document.title,
                "paths": list(item.document.paths),
                "target": item.target,
                "reason": item.reason,
                "candidates": list(item.candidates),
            }
            for item in plan
        ],
        "unresolved": errors,
    }


def main() -> int:
    args = parse_args()
    source = args.source.resolve()
    out = args.out.resolve()
    if not out.is_dir():
        raise SystemExit(f"文档仓库目录不存在: {out}")
    if source == out or out in source.parents or source in out.parents:
        raise SystemExit("临时目录和文档仓库必须互不包含，请使用仓库之外的临时目录")
    layout_path = (args.layout or (out / ".api-doc-layout.json")).resolve()
    layout = load_layout(layout_path)
    documents = read_documents(source)
    if not documents:
        raise SystemExit(f"临时目录中没有待归档文档: {source}")
    plan = [classify(document, layout) for document in documents]
    errors = validate_plan(plan, out)
    payload = result_payload(plan, errors)
    if args.json:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        for item in plan:
            target = item.target or "<AI 需要判断>"
            print(f"{item.document.source.name} -> {target} ({item.reason})")
        if errors:
            print(f"需要 AI 分类或处理冲突: {len(errors)} 个")
    if errors:
        return 2
    if args.dry_run:
        return 0
    execute(plan, out, layout)
    if not args.json:
        print(f"归档完成: {len(plan)} 个文件，README 已更新")
    return 0


if __name__ == "__main__":
    sys.exit(main())
