"""Экспорт схем FastAPI в версионируемые спецификации для разработчиков и генераторов клиентов."""

import argparse
from pathlib import Path

import yaml

from app import app as encoder
from search_contract.api import app as search


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check", action="store_true", help="Проверить совпадение с сохранённым YAML."
    )
    args = parser.parse_args()
    directory = Path(__file__).resolve().parents[1] / "contracts"
    if not args.check:
        directory.mkdir(exist_ok=True)
    drift = False
    for name, application in (("search", search), ("model", encoder)):
        path = directory / f"{name}.openapi.yaml"
        content = yaml.safe_dump(
            application.openapi(), sort_keys=False, allow_unicode=True, width=110
        )
        if args.check:
            if not path.is_file() or path.read_text(encoding="utf-8") != content:
                print(f"Обновите {path.name} командой uv run --group docs python export_openapi.py")
                drift = True
        else:
            path.write_text(content, encoding="utf-8", newline="\n")
    return int(drift)


if __name__ == "__main__":
    raise SystemExit(main())
