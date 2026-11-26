"""Проверка смыслового поиска на небольшом синтетическом каталоге через HTTP API энкодера."""

import argparse
import json
import math
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

DEMO_CATALOG = (
    ("backup", "Резервное копирование данных и восстановление потерянных файлов после сбоя."),
    ("printing", "Монохромный лазерный принтер для печати офисных документов на бумаге."),
    ("antivirus", "Антивирусная защита рабочих станций от вредоносных программ."),
    ("remote-desktop", "Удалённый доступ к рабочему столу офисного компьютера из дома."),
)


def request_json(base_url, path, payload=None):
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
    request = Request(
        base_url.rstrip("/") + path, data=body, headers={"Content-Type": "application/json"}
    )
    with urlopen(request, timeout=60) as response:
        return json.load(response)


def encode(base_url, profile, input_type, texts):
    response = request_json(
        base_url,
        "/internal/v1/embeddings",
        {
            "input_type": input_type,
            "profile_id": profile,
            "texts": texts,
        },
    )
    vectors = response["vectors"]
    if (
        response["profile_id"] != profile
        or response["dimension"] != 384
        or len(vectors) != len(texts)
    ):
        raise ValueError("Профиль, размерность или число ответов энкодера изменились.")
    for vector in vectors:
        if len(vector) != 384 or not math.isclose(math.hypot(*vector), 1, abs_tol=1e-4):
            raise ValueError("Энкодер вернул некорректный вектор.")
    return vectors


def wait_for_profile(base_url):
    deadline = time.monotonic() + 60
    while True:
        try:
            return request_json(base_url, "/ready")["profile_id"]
        except HTTPError as error:
            if error.code != 503 or time.monotonic() >= deadline:
                raise
            error.close()
        time.sleep(1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("query", help="Задача покупателя на естественном языке.")
    parser.add_argument("--base-url", default="http://127.0.0.1:8000", help="Адрес FastAPI.")
    args = parser.parse_args()
    try:
        profile = wait_for_profile(args.base_url)
        documents = encode(args.base_url, profile, "passage", [text for _, text in DEMO_CATALOG])
        (query,) = encode(args.base_url, profile, "query", [args.query])
    except HTTPError as error:
        parser.exit(
            1, f"FastAPI вернул HTTP {error.code}. {error.read().decode('utf-8', 'replace')}\n"
        )
    except (URLError, TimeoutError, ValueError, KeyError) as error:
        parser.exit(1, f"Не удалось получить эмбеддинги. {error}\n")

    ranked = sorted(
        (
            (math.sumprod(query, vector), key, text)
            for (key, text), vector in zip(DEMO_CATALOG, documents, strict=True)
        ),
        reverse=True,
    )
    for score, key, text in ranked:
        print(f"{score:.4f}  {key:<14} {text}")


if __name__ == "__main__":
    main()
