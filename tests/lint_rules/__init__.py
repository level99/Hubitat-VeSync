"""
Lint rule modules for the Hubitat-VeSync static analyzer.

Each module exposes a list of Rule objects. Rules are functions from
(path: Path, lines: list[str], config: dict) -> list[Finding].
"""
