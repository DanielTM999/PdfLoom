from pathlib import Path
import re


TOKEN = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[A-Za-z_$][\w$]*|[^\s]', re.MULTILINE)


def format_source(source):
    tokens = list(TOKEN.finditer(source))
    edits = []
    for index, token in enumerate(tokens[:-1]):
        following = tokens[index + 1]
        if token.group() == "{" and following.group() == "}":
            prefix = source[source.rfind("\n", 0, token.start()) + 1:token.start()]
            indent = re.match(r"\s*", prefix).group()
            edits.append((token.end(), following.start(), "\n" + indent))
        if token.group() == "@":
            end_index = index + 1
            while end_index + 2 < len(tokens) and tokens[end_index + 1].group() == ".":
                end_index += 2
            if end_index + 1 < len(tokens) and tokens[end_index + 1].group() == "(":
                depth = 0
                for cursor in range(end_index + 1, len(tokens)):
                    if tokens[cursor].group() == "(":
                        depth += 1
                    elif tokens[cursor].group() == ")":
                        depth -= 1
                        if depth == 0:
                            end_index = cursor
                            break
            if end_index + 1 < len(tokens):
                end = tokens[end_index].end()
                next_start = tokens[end_index + 1].start()
                if "\n" not in source[end:next_start]:
                    prefix = source[source.rfind("\n", 0, token.start()) + 1:token.start()]
                    indent = re.match(r"\s*", prefix).group()
                    edits.append((end, next_start, "\n" + indent))
    for start, end, text in sorted(edits, reverse=True):
        source = source[:start] + text + source[end:]
    return source


if __name__ == "__main__":
    directories = [Path("src/main/java"), Path("src/test/java"), Path("src/test/tools")]
    local_references = Path("local-reference")
    if local_references.is_dir():
        for reference in sorted(item for item in local_references.iterdir() if item.is_dir()):
            directories += [reference / "java", reference / "tools"]
    for directory in directories:
        for path in directory.rglob("*.java"):
            source = path.read_text(encoding="utf-8")
            path.write_text(format_source(source), encoding="utf-8")
